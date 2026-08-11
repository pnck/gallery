package io.github.pnck.gallery.provider.mapper

import io.github.pnck.gallery.provider.CloudFile
import io.github.pnck.gallery.provider.ContentHash
import io.github.pnck.gallery.provider.ProviderType
import io.github.pnck.gallery.provider.dto.DriveFileDTO
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * DTO → unified CloudFile normalization for Google Drive (PRD §3.1, §4.2).
 * Kept free of Android imports so it stays JVM-unit-testable.
 */
object DriveMappers {

    /**
     * Drive's imageMediaMetadata.time is EXIF-formatted ("yyyy:MM:dd HH:mm:ss").
     * EXIF carries no timezone; UTC is assumed for determinism.
     */
    fun parseExifTime(time: String?): Long? {
        if (time.isNullOrBlank()) return null
        val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        return try {
            format.parse(time)?.time
        } catch (_: ParseException) {
            null
        }
    }

    fun toCloudFile(dto: DriveFileDTO, fallbackCreationTime: Long = 0L): CloudFile =
        CloudFile(
            id = dto.id,
            provider = ProviderType.G_DRIVE,
            contentHash = dto.md5Checksum?.let { ContentHash.Md5(it) } ?: ContentHash.None,
            sizeBytes = dto.size ?: 0L,
            creationTime = parseExifTime(dto.imageMediaMetadata?.time) ?: fallbackCreationTime,
            width = dto.imageMediaMetadata?.width ?: 0,
            height = dto.imageMediaMetadata?.height ?: 0,
            thumbnailUrl = dto.thumbnailLink,
            name = dto.name,
            sourcePath = dto.appProperties?.get("sourcePath"),
            isVideo = dto.mimeType?.startsWith("video/") == true,
            durationMs = dto.videoMediaMetadata?.durationMillis?.toLongOrNull() ?: 0L,
        )
}
