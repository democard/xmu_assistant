package com.xmu.assistant

internal fun <T, R> downloadCoursewareInParallel(
    items: List<T>,
    download: (T) -> R,
): List<R> = boundedParallelMap(
    items = items,
    maxParallel = 2,
    transform = download,
)
