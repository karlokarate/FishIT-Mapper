package dev.fishit.mapper.network

interface MapperNativeHttpClient {
    fun execute(request: MapperNativeHttpRequest): MapperNativeHttpResponse
}
