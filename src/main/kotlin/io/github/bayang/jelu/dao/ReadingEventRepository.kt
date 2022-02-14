package io.github.bayang.jelu.dao

import io.github.bayang.jelu.dto.CreateReadingEventDto
import io.github.bayang.jelu.dto.UpdateReadingEventDto
import io.github.bayang.jelu.errors.JeluException
import io.github.bayang.jelu.utils.nowInstant
import mu.KotlinLogging
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Repository
class ReadingEventRepository {

    fun findAll(
        eventTypes: List<ReadingEventType>?,
        userId: UUID?,
        pageable: Pageable
    ): Page<ReadingEvent> {
        val query = ReadingEventTable.join(UserBookTable, JoinType.LEFT)
            .selectAll()
        if (eventTypes != null && eventTypes.isNotEmpty()) {
            query.andWhere { ReadingEventTable.eventType inList eventTypes }
        }
        if (userId != null) {
            query.andWhere { UserBookTable.user eq userId }
        }
        val total = query.count()
        query.limit(pageable.pageSize, pageable.offset)
        val orders: Array<Pair<Expression<*>, SortOrder>> = parseSorts(pageable.sort, Pair(ReadingEventTable.modificationDate, SortOrder.DESC_NULLS_LAST), ReadingEventTable)
        query.orderBy(*orders)
        return PageImpl(
            ReadingEvent.wrapRows(query).toList(),
            pageable,
            total
        )
    }

    fun save(createReadingEventDto: CreateReadingEventDto, targetUser: User): ReadingEvent {
        if (createReadingEventDto.bookId == null) {
            throw JeluException("Missing bookId to create reading event")
        }
        val foundBook: Book = Book[createReadingEventDto.bookId]
        return save(createReadingEventDto, foundBook, targetUser)
    }

    fun save(createReadingEventDto: CreateReadingEventDto, book: Book, targetUser: User): ReadingEvent {
        var found: UserBook? =
            UserBook.find { UserBookTable.user eq targetUser.id and (UserBookTable.book.eq(book.id)) }.firstOrNull()
        val instant: Instant = nowInstant()
        if (found == null) {
            found = UserBook.new {
                this.creationDate = instant
                this.user = targetUser
                this.book = book
            }
        }
        found.modificationDate = instant
        return save(found, createReadingEventDto)
    }

    fun save(userBook: UserBook, createReadingEventDto: CreateReadingEventDto): ReadingEvent {
        val alreadyReadingEvent: ReadingEvent? =
            userBook.readingEvents.find { it.eventType == ReadingEventType.CURRENTLY_READING }
        val instant: Instant = nowInstant()
        if (userBook.lastReadingEvent != null) {
            if (createReadingEventDto.readDate != null && createReadingEventDto.readDate.isAfter(userBook.lastReadingEventDate)) {
                userBook.lastReadingEvent = createReadingEventDto.eventType
                userBook.lastReadingEventDate = createReadingEventDto.readDate
            }
        } else {
            userBook.lastReadingEvent = createReadingEventDto.eventType
            userBook.lastReadingEventDate = createReadingEventDto.readDate ?: instant
        }
        if (alreadyReadingEvent != null) {
            if (createReadingEventDto.readDate == null || createReadingEventDto.readDate.isAfter(alreadyReadingEvent.creationDate)) {
                logger.debug { "found ${userBook.readingEvents.count()} older events in CURRENTLY_READING state for book ${userBook.book.id}" }
                alreadyReadingEvent.eventType = createReadingEventDto.eventType
                alreadyReadingEvent.modificationDate = instant
                return alreadyReadingEvent
            }
        }
        return ReadingEvent.new {
            this.creationDate = createReadingEventDto.readDate ?: instant
            this.modificationDate = instant
            this.eventType = createReadingEventDto.eventType
            this.userBook = userBook
        }
    }

    fun updateReadingEvent(readingEventId: UUID, updateReadingEventDto: UpdateReadingEventDto): ReadingEvent {
        return ReadingEvent[readingEventId].apply {
            this.modificationDate = nowInstant()
            this.eventType = updateReadingEventDto.eventType
            this.userBook.lastReadingEvent = updateReadingEventDto.eventType
            this.userBook.lastReadingEventDate = this.modificationDate
        }
    }

    fun deleteReadingEventById(eventId: UUID) {
        val entity: ReadingEvent = ReadingEvent[eventId]
        val userbook = entity.userBook
        entity.delete()
        val lastEvent = userbook.readingEvents
            .maxByOrNull { e -> e.modificationDate }
        if (lastEvent == null) {
            userbook.lastReadingEvent = null
            userbook.lastReadingEventDate = null
        } else {
            userbook.lastReadingEventDate = lastEvent.modificationDate
            userbook.lastReadingEvent = lastEvent.eventType
        }
    }
}
