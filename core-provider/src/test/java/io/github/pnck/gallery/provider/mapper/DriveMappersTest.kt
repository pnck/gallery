package io.github.pnck.gallery.provider.mapper

import io.github.pnck.gallery.provider.ContentHash
import io.github.pnck.gallery.provider.ProviderType
import io.github.pnck.gallery.provider.dto.DriveFileDTO
import io.github.pnck.gallery.provider.dto.ImageMetadataDTO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveMappersTest {

    @Test
    fun `parses EXIF time as UTC epoch`() {
        // 2026-07-06 12:00:00 UTC
        assertEquals(1783339200000L, DriveMappers.parseExifTime("2026:07:06 12:00:00"))
    }

    @Test
    fun `invalid or missing EXIF time returns null`() {
        assertNull(DriveMappers.parseExifTime(null))
        assertNull(DriveMappers.parseExifTime(""))
        assertNull(DriveMappers.parseExifTime("2026-07-06T12:00:00Z"))
        assertNull(DriveMappers.parseExifTime("not a date"))
    }

    @Test
    fun `maps DTO to unified CloudFile with md5 hash`() {
        val dto = DriveFileDTO(
            id = "file123",
            name = "IMG_0001.jpg",
            size = 4_200_000L,
            md5Checksum = "d41d8cd98f00b204e9800998ecf8427e",
            thumbnailLink = "https://lh3.googleusercontent.com/thumb",
            imageMediaMetadata = ImageMetadataDTO(width = 4000, height = 3000, time = "2026:07:06 12:00:00"),
        )

        val cloud = DriveMappers.toCloudFile(dto)

        assertEquals("file123", cloud.id)
        assertEquals(ProviderType.G_DRIVE, cloud.provider)
        assertEquals(ContentHash.Md5("d41d8cd98f00b204e9800998ecf8427e"), cloud.contentHash)
        assertEquals(4_200_000L, cloud.sizeBytes)
        assertEquals(1783339200000L, cloud.creationTime)
        assertEquals(4000, cloud.width)
        assertEquals(3000, cloud.height)
        assertEquals("https://lh3.googleusercontent.com/thumb", cloud.thumbnailUrl)
    }

    @Test
    fun `missing metadata falls back to defaults`() {
        val cloud = DriveMappers.toCloudFile(DriveFileDTO(id = "x"), fallbackCreationTime = 42L)

        assertEquals(ContentHash.None, cloud.contentHash)
        assertEquals(0L, cloud.sizeBytes)
        assertEquals(42L, cloud.creationTime)
        assertEquals(0, cloud.width)
        assertNull(cloud.thumbnailUrl)
    }
}
