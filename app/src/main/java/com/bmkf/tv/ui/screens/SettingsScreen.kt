package com.bmkf.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.ui.platform.LocalContext
import coil.Coil
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmkf.tv.data.model.SourceId
import com.bmkf.tv.data.model.VideoScaleMode
import com.bmkf.tv.data.storage.LocalStorage
import com.bmkf.tv.ui.components.SourceBadge
import com.bmkf.tv.ui.components.TvActionButton
import com.bmkf.tv.ui.components.TvFilterChip
import com.bmkf.tv.ui.theme.CinemaAccent
import com.bmkf.tv.ui.theme.CinemaBorderSubtle
import com.bmkf.tv.ui.theme.CinemaGold
import com.bmkf.tv.ui.theme.CinemaSurface
import com.bmkf.tv.ui.theme.CinemaSurfaceElevated
import com.bmkf.tv.ui.theme.CinemaTextMuted
import com.bmkf.tv.ui.theme.CinemaTextPrimary

@Composable
fun SettingsScreen(storage: LocalStorage) {
    val context = LocalContext.current
    val settings by storage.settings.collectAsState()

    var kkUrl by remember(settings.kkPhimBaseUrl) { mutableStateOf(settings.kkPhimBaseUrl) }
    var vsUrl by remember(settings.vsMovBaseUrl) { mutableStateOf(settings.vsMovBaseUrl) }
    var cbApiUrl by remember(settings.coBeApiBaseUrl) { mutableStateOf(settings.coBeApiBaseUrl) }
    var cbWebUrl by remember(settings.coBeWebBaseUrl) { mutableStateOf(settings.coBeWebBaseUrl) }
    var k20Url by remember(settings.k20ManifestUrl) { mutableStateOf(settings.k20ManifestUrl) }
    var showBoxUrl by remember(settings.showBoxManifestUrl) { mutableStateOf(settings.showBoxManifestUrl) }
    var khoPhimApiUrl by remember(settings.khoPhimApiBaseUrl) { mutableStateOf(settings.khoPhimApiBaseUrl) }
    var khoPhimApiKey by remember(settings.khoPhimApiKey) { mutableStateOf(settings.khoPhimApiKey) }
    var khoPhimWebUrl by remember(settings.khoPhimWebBaseUrl) { mutableStateOf(settings.khoPhimWebBaseUrl) }
    var sportTvUrl by remember(settings.sportTvUrl) { mutableStateOf(settings.sportTvUrl) }
    var highFlyUrl by remember(settings.highFlySportUrl) { mutableStateOf(settings.highFlySportUrl) }
    var xoiLacUrl by remember(settings.xoiLacUrl) { mutableStateOf(settings.xoiLacUrl) }
    var gaVangUrl by remember(settings.gaVangUrl) { mutableStateOf(settings.gaVangUrl) }
    var xoiCheUrl by remember(settings.xoiCheUrl) { mutableStateOf(settings.xoiCheUrl) }
    var colaTvUrl by remember(settings.colaTvUrl) { mutableStateOf(settings.colaTvUrl) }
    var savedBanner by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 28.dp, top = 20.dp),
        contentPadding = PaddingValues(bottom = 42.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text(
                text = "Cài đặt ứng dụng & Nguồn phim",
                color = CinemaTextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "Bật/tắt nguồn, tùy chỉnh URL API hoặc cấu hình trình phát TV",
                color = CinemaTextMuted,
                fontSize = 13.sp
            )
        }

        // Source Toggles
        item {
            SectionHeading("Nguồn phim đang hoạt động")
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceId.entries.forEach { source ->
                    val enabled = source in settings.enabledSources
                    SettingsToggleRow(
                        title = source.displayName,
                        subtitle = source.description,
                        badge = { SourceBadge(source = source, compact = true) },
                        checked = enabled,
                        onToggle = {
                            storage.updateSettings { current ->
                                val next = current.enabledSources.toMutableSet()
                                if (source in next && next.size > 1) next.remove(source) else next.add(source)
                                current.copy(enabledSources = next)
                            }
                        }
                    )
                }
            }
        }

        // Player Preferences
        item {
            SectionHeading("Trình phát & Phụ đề")
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsToggleRow(
                    title = "Tự động chuyển tập tiếp theo",
                    subtitle = "Hiển thị đếm ngược 5 giây và phát tập kế tiếp khi hết phim",
                    checked = settings.autoPlayNext,
                    onToggle = {
                        storage.updateSettings { it.copy(autoPlayNext = !it.autoPlayNext) }
                    }
                )
                SettingsToggleRow(
                    title = "Bật phụ đề mặc định",
                    subtitle = "Tự động tải phụ đề Tiếng Việt (.vtt/.srt) từ VSMOV, K20 & ShowBox",
                    checked = settings.subtitleEnabled,
                    onToggle = {
                        storage.updateSettings { it.copy(subtitleEnabled = !it.subtitleEnabled) }
                    }
                )
                SettingsToggleRow(
                    title = "Chống tím màu (Tự tránh Dolby Vision)",
                    subtitle = "Tự bỏ qua luồng Dolby Vision Profile 5 gây tím/xanh màu trên TV không hỗ trợ DV",
                    checked = settings.safeColorMode,
                    onToggle = {
                        storage.updateSettings { it.copy(safeColorMode = !it.safeColorMode) }
                    }
                )
                SettingsToggleRow(
                    title = "Tự đổi server khi bị lag / đứng hình",
                    subtitle = "Đo tốc độ song song và tự động chuyển server khác nếu Sport TV/phim bị giật liên tục",
                    checked = settings.autoSwitchOnLag,
                    onToggle = {
                        storage.updateSettings { it.copy(autoSwitchOnLag = !it.autoSwitchOnLag) }
                    }
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("Cỡ chữ phụ đề:", color = CinemaTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    listOf(0.85f to "Nhỏ", 1.0f to "Chuẩn", 1.25f to "Lớn", 1.5f to "Rất lớn").forEach { (scale, label) ->
                        TvFilterChip(
                            label = label,
                            selected = settings.subtitleTextScale == scale,
                            onClick = { storage.updateSettings { it.copy(subtitleTextScale = scale) } }
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Text("Tỉ lệ màn hình:", color = CinemaTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    VideoScaleMode.entries.forEach { mode ->
                        TvFilterChip(
                            label = mode.label,
                            selected = settings.defaultScaleMode == mode,
                            onClick = { storage.updateSettings { it.copy(defaultScaleMode = mode) } }
                        )
                    }
                }
            }
        }

        // Editable Endpoint URLs
        item {
            SectionHeading("Địa chỉ API / Manifest (Có thể đổi nếu domain thay đổi)")
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EndpointTextField("KKPhim Base URL", kkUrl) { kkUrl = it }
                EndpointTextField("VSMOV API URL", vsUrl) { vsUrl = it }
                EndpointTextField("CoBePhim API URL", cbApiUrl) { cbApiUrl = it }
                EndpointTextField("CoBePhim Web URL", cbWebUrl) { cbWebUrl = it }
                EndpointTextField("K20 Stremio Manifest URL", k20Url) { k20Url = it }
                EndpointTextField("ShowBox Stremio Manifest URL", showBoxUrl) { showBoxUrl = it }
                EndpointTextField("KhoPhim Publisher API URL", khoPhimApiUrl) { khoPhimApiUrl = it }
                EndpointTextField("KhoPhim API Key (pk_live_... — để trống dùng bản Web)", khoPhimApiKey) { khoPhimApiKey = it }
                EndpointTextField("KhoPhim Web URL", khoPhimWebUrl) { khoPhimWebUrl = it }
                EndpointTextField("Sport TV URL (Nuvio collections.json)", sportTvUrl) { sportTvUrl = it }
                EndpointTextField("Sport TV URL (HighFly manifest.json)", highFlyUrl) { highFlyUrl = it }
                EndpointTextField("Sport TV URL (Xôi Lạc TV)", xoiLacUrl) { xoiLacUrl = it }
                EndpointTextField("Sport TV URL (Gà Vàng TV)", gaVangUrl) { gaVangUrl = it }
                EndpointTextField("Sport TV URL (Xôi Chè TV)", xoiCheUrl) { xoiCheUrl = it }
                EndpointTextField("Sport TV URL (Cola TV)", colaTvUrl) { colaTvUrl = it }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvActionButton(
                    label = "Lưu cấu hình",
                    icon = Icons.Default.Save,
                    primary = true,
                    onClick = {
                        storage.updateSettings {
                            it.copy(
                                kkPhimBaseUrl = kkUrl.trim().ifBlank { it.kkPhimBaseUrl },
                                vsMovBaseUrl = vsUrl.trim().ifBlank { it.vsMovBaseUrl },
                                coBeApiBaseUrl = cbApiUrl.trim().ifBlank { it.coBeApiBaseUrl },
                                coBeWebBaseUrl = cbWebUrl.trim().ifBlank { it.coBeWebBaseUrl },
                                k20ManifestUrl = k20Url.trim().ifBlank { it.k20ManifestUrl },
                                showBoxManifestUrl = showBoxUrl.trim().ifBlank { it.showBoxManifestUrl },
                                khoPhimApiBaseUrl = khoPhimApiUrl.trim().ifBlank { it.khoPhimApiBaseUrl },
                                khoPhimApiKey = khoPhimApiKey.trim(),
                                khoPhimWebBaseUrl = khoPhimWebUrl.trim().ifBlank { it.khoPhimWebBaseUrl },
                                sportTvUrl = sportTvUrl.trim().ifBlank { it.sportTvUrl },
                                highFlySportUrl = highFlyUrl.trim().ifBlank { it.highFlySportUrl },
                                xoiLacUrl = xoiLacUrl.trim().ifBlank { it.xoiLacUrl },
                                gaVangUrl = gaVangUrl.trim().ifBlank { it.gaVangUrl },
                                xoiCheUrl = xoiCheUrl.trim().ifBlank { it.xoiCheUrl },
                                colaTvUrl = colaTvUrl.trim().ifBlank { it.colaTvUrl }
                            )
                        }
                        savedBanner = "Đã lưu cấu hình nguồn phim thành công!"
                    }
                )
                TvActionButton(
                    label = "Khôi phục mặc định",
                    icon = Icons.Default.RestartAlt,
                    primary = false,
                    onClick = {
                        storage.resetSettings()
                        savedBanner = "Đã khôi phục cấu hình mặc định."
                    }
                )
                TvActionButton(
                    label = "Xóa cache ảnh",
                    icon = Icons.Default.CleaningServices,
                    primary = false,
                    onClick = {
                        val loader = Coil.imageLoader(context)
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                        savedBanner = "Đã giải phóng bộ nhớ đệm ảnh."
                    }
                )
            }

            savedBanner?.let { msg ->
                Spacer(Modifier.height(10.dp))
                Text(msg, color = CinemaGold, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        color = CinemaGold,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    badge: (@Composable () -> Unit)? = null,
    onToggle: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) CinemaSurfaceElevated else CinemaSurface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else CinemaBorderSubtle,
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onToggle)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                badge?.invoke()
                Text(title, color = CinemaTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = CinemaTextMuted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = CinemaAccent,
                uncheckedThumbColor = CinemaTextMuted,
                uncheckedTrackColor = CinemaBorderSubtle
            )
        )
    }
}

@Composable
private fun EndpointTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = CinemaTextMuted) },
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = CinemaSurface,
            unfocusedContainerColor = CinemaSurface,
            focusedTextColor = CinemaTextPrimary,
            unfocusedTextColor = CinemaTextPrimary,
            focusedBorderColor = Color.White,
            unfocusedBorderColor = CinemaBorderSubtle,
            cursorColor = CinemaAccent
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
