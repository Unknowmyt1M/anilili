package com.anilili.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ShortsItem(
    @SerialName("id") val id: String,
    @SerialName("youtubeVideoId") val youtubeVideoId: String = "",
    @SerialName("youtubeUrl") val youtubeUrl: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("description") val description: String? = null,
    @SerialName("channelName") val channelName: String? = null,
    @SerialName("thumbnailUrl") val thumbnailUrl: String? = null,
    @SerialName("anilistId") val anilistId: Int? = null,
    @SerialName("seriesTitle") val seriesTitle: String? = null,
    @SerialName("posterUrl") val posterUrl: String? = null,
    @SerialName("episodeNumber") val episodeNumber: Int? = null,
    @SerialName("videoUrl") val videoUrl: String? = null,
    @SerialName("streamType") val streamType: String = "MP4",
    @SerialName("streamExpiresAt") val streamExpiresAt: String? = null,
    @SerialName("likeCount") val likeCount: Int = 0,
    @SerialName("dislikeCount") val dislikeCount: Int = 0,
    @SerialName("userReaction") val userReaction: String? = "NONE",
    @SerialName("resolutionMethod") val resolutionMethod: String? = null,
    @SerialName("resolutionConfidence") val resolutionConfidence: Float = 0.0f,
    @SerialName("isUnavailable") val isUnavailable: Boolean = false,
)

@Serializable
data class ShortsPage(
    @SerialName("items") val items: List<ShortsItem> = emptyList(),
    @SerialName("nextCursor") val nextCursor: String? = null,
    @SerialName("isReplenishing") val isReplenishing: Boolean = false,

)

@Serializable
data class StreamResponse(
    @SerialName("shortId") val shortId: String = "",
    @SerialName("streamStatus") val streamStatus: String = "READY",
    @SerialName("videoUrl") val videoUrl: String? = null,
    @SerialName("streamType") val streamType: String? = "MP4",
    @SerialName("expiresAt") val streamExpiresAt: String? = null,
)

@Serializable
data class ReactionRequest(
    @SerialName("reaction") val reaction: String,
    @SerialName("userId") val userId: String = "anonymous",
)

@Serializable
data class ReactionResponse(
    @SerialName("shortId") val shortId: String,
    @SerialName("likeCount") val likeCount: Int,
    @SerialName("dislikeCount") val dislikeCount: Int,
    @SerialName("userReaction") val userReaction: String,
)
