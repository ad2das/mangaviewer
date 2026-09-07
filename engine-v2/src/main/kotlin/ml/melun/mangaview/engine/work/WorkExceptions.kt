package ml.melun.mangaview.engine.work

class WorkCoordinatorClosedException : IllegalStateException("Work coordinator is closed")

class WorkQueueFullException(limit: Int) :
    IllegalStateException("Work queue is full (limit=$limit)")

class WorkRequestConflictException(message: String) : IllegalStateException(message)

class WorkAuthEpochRetiredException(epoch: Long) :
    IllegalStateException("Work authentication epoch has been retired: $epoch")

class WorkRetiringException(key: Any) :
    IllegalStateException("Work key is still retiring: $key")

class WorkResultTypeMismatchException(
    expected: Class<*>,
    actual: Class<*>?,
) : IllegalStateException(
    "Work result type mismatch: expected=${expected.name} actual=${actual?.name ?: "null"}",
)
