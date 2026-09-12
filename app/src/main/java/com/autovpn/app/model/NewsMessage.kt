package com.autovpn.app.model

data class NewsMessage(
    val id: Long,
    val text: String,
    val date: String?,
    val link: String?
)
