package com.xmu.assistant

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import java.io.FileOutputStream
import java.io.OutputStream
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ScoreMediaStoreRegressionTest.ExactResolverShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScoreMediaStoreRegressionTest {
    // Robolectric's one-argument shadow swallows FileNotFoundException/SecurityException
    // and supplies a no-op stream. Android delegates directly to the two-argument method.
    @Implements(ContentResolver::class)
    class ExactResolverShadow : ShadowContentResolver() {
        @Implementation
        public override fun openOutputStream(uri: Uri): OutputStream? = super.openOutputStream(uri, "w")
    }
    private class MediaProvider(private val output: File) : ContentProvider() {
        var insertFailure: RuntimeException? = null
        var returnNull = false
        var openFailure = false
        var publishFailure = false
        var deleteFailure = false
        val inserted = mutableListOf<ContentValues>()
        val published = mutableListOf<ContentValues>()
        val deleted = mutableListOf<Uri>()
        var opened = 0
        val imageUri: Uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "73")

        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            inserted += ContentValues(values)
            insertFailure?.let { throw it }
            return if (returnNull) null else imageUri
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
                            selectionArgs: Array<out String>?): Int {
            published += ContentValues(values)
            if (publishFailure) throw SecurityException("fixture publish denied")
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted += uri
            if (deleteFailure) throw SecurityException("fixture cleanup denied")
            output.delete()
            return 1
        }
    }

    private class Fixture {
        val context: Context = RuntimeEnvironment.getApplication()
        val output = File.createTempFile("score-media-", ".png", context.cacheDir)
        val provider = MediaProvider(output).also {
            it.attachInfo(context, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
            ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, it)
            shadowOf(context.contentResolver).registerOutputStreamSupplier(it.imageUri) {
                it.opened += 1
                if (it.openFailure) throw FileNotFoundException("fixture storage unavailable")
                FileOutputStream(output)
            }
        }
        fun save(): Uri? {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            return try { saveScoreImageToGallery(context, bitmap) } finally { bitmap.recycle() }
        }
    }

    @Test
    fun `insert exceptions return failure instead of escaping the share coroutine`() {
        for (error in listOf(SecurityException("fixture insert denied"),
                             IllegalArgumentException("fixture volume unavailable"))) {
            val fixture = Fixture()
            fixture.provider.insertFailure = error
            val result = runCatching { fixture.save() }
            assertNull("insert must be inside the failure boundary", result.exceptionOrNull())
            assertNull(result.getOrNull())
            assertEquals(0, fixture.provider.opened)
            assertTrue(fixture.provider.deleted.isEmpty())
        }
    }

    @Test
    fun `successful image stays pending until PNG writing has finished`() {
        val fixture = Fixture()
        val uri = fixture.save()
        assertEquals(fixture.provider.imageUri, uri)
        assertTrue(fixture.output.length() > 0)
        assertEquals(1, fixture.provider.inserted.single().getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals(0, fixture.provider.published.single().getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertTrue(fixture.provider.deleted.isEmpty())
    }

    @Test
    fun `failed output opening deletes the pending entry`() {
        val fixture = Fixture()
        fixture.provider.openFailure = true
        assertNull(fixture.save())
        assertEquals(listOf(fixture.provider.imageUri), fixture.provider.deleted)
        assertTrue(fixture.provider.published.isEmpty())
    }

    @Test
    fun `failed publication never returns a broken or pending share URI`() {
        val fixture = Fixture()
        fixture.provider.publishFailure = true
        assertNull(fixture.save())
        assertEquals(listOf(fixture.provider.imageUri), fixture.provider.deleted)
        assertFalse(fixture.output.exists())
    }

    @Test
    fun `cleanup exceptions do not escape a failed media write`() {
        val fixture = Fixture()
        fixture.provider.openFailure = true
        fixture.provider.deleteFailure = true
        val result = runCatching { fixture.save() }
        assertNull(result.exceptionOrNull())
        assertNull(result.getOrNull())
        assertEquals(listOf(fixture.provider.imageUri), fixture.provider.deleted)
    }

    @Test
    fun `null insert result does not open or publish any image`() {
        val fixture = Fixture()
        fixture.provider.returnNull = true
        assertNull(fixture.save())
        assertEquals(0, fixture.provider.opened)
        assertTrue(fixture.provider.published.isEmpty())
        assertTrue(fixture.provider.deleted.isEmpty())
        assertNotNull(fixture.provider.inserted.single())
    }
}
