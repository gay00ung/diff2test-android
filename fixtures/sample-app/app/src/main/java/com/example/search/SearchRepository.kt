package com.example.search

interface SearchRepository {
    suspend fun search(query: String): Result<List<String>>
}
