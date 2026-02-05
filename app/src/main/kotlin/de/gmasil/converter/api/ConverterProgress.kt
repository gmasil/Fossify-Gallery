package de.gmasil.converter.api

@FunctionalInterface
fun interface ConverterProgress {
    fun accept(progressPercent: Float, status: String)
}
