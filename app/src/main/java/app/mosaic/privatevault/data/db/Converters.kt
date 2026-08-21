package app.mosaic.privatevault.data.db

import androidx.room.TypeConverter
import app.mosaic.privatevault.domain.model.DeliveryStatus
import app.mosaic.privatevault.domain.model.MediaKind
import app.mosaic.privatevault.domain.model.MessageDirection
import app.mosaic.privatevault.domain.model.MessageSource

/**
 * Enums are stored by name rather than by ordinal: reordering a Kotlin enum
 * must never silently rewrite the meaning of rows already in the vault.
 */
class Converters {
    @TypeConverter fun directionToString(value: MessageDirection): String = value.name

    @TypeConverter fun stringToDirection(value: String): MessageDirection =
        MessageDirection.entries.firstOrNull { it.name == value } ?: MessageDirection.INCOMING

    @TypeConverter fun mediaToString(value: MediaKind): String = value.name

    @TypeConverter fun stringToMedia(value: String): MediaKind =
        MediaKind.entries.firstOrNull { it.name == value } ?: MediaKind.UNAVAILABLE

    @TypeConverter fun statusToString(value: DeliveryStatus): String = value.name

    @TypeConverter fun stringToStatus(value: String): DeliveryStatus =
        DeliveryStatus.entries.firstOrNull { it.name == value } ?: DeliveryStatus.RECEIVED

    @TypeConverter fun sourceToString(value: MessageSource): String = value.name

    @TypeConverter fun stringToSource(value: String): MessageSource =
        MessageSource.entries.firstOrNull { it.name == value } ?: MessageSource.ANDROID_NOTIFICATION
}
