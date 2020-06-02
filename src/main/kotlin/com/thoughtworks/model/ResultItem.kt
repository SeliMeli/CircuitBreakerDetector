package com.thoughtworks.model

data class ResultItem(
    val endpoint: String,
    val circuitBreakers: Set<String>
)
