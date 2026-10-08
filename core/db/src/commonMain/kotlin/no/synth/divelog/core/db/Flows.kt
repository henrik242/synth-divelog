package no.synth.divelog.core.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Re-run [this] query whenever a table it reads changes, mapping rows to domain values.

internal fun <T : Any, R> Query<T>.listFlow(map: (T) -> R): Flow<List<R>> =
    asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map(map) }

internal fun <T : Any, R> Query<T>.oneOrNullFlow(map: (T) -> R): Flow<R?> =
    asFlow().mapToOneOrNull(Dispatchers.Default).map { it?.let(map) }
