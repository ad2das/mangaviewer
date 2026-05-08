package ml.melun.mangaview.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryResultTest {
    @Test
    fun successCarriesRepositoryValue() {
        val result: RepositoryResult<Int> = RepositoryResult.Success(7)
        assertTrue(result is RepositoryResult.Success)
        assertEquals(7, (result as RepositoryResult.Success).value)
    }

    @Test
    fun failureClassifiesStorageErrors() {
        val result: RepositoryResult<Int> = RepositoryResult.Failure(MangaFailure.StorageError())
        assertTrue((result as RepositoryResult.Failure).failure is MangaFailure.StorageError)
    }
}
