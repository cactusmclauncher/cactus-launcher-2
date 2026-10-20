package com.cactuslauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cactuslauncher.model.PlayerProfile
import com.cactuslauncher.model.ProfileSnapshot
import com.cactuslauncher.model.isValidPlayerName
import com.cactuslauncher.theme.C

@Composable
fun ProfilesDialog(
    snapshot: ProfileSnapshot?, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onRetry: () -> Unit,
    onAdd: (String) -> Unit, onRename: (String, String) -> Unit,
    onSelect: (String) -> Unit, onDelete: (String) -> Unit,
    externalAccountName: String? = null,
    advancedMode: Boolean = false,
    onSignInExternal: (String, String, String) -> Unit = { _, _, _ -> },
    onSignOutExternal: () -> Unit = {},
) {
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<PlayerProfile?>(null) }
    var deleting by remember { mutableStateOf<PlayerProfile?>(null) }
    var microsoftUnavailable by remember { mutableStateOf(false) }
    var authlibLoginOpen by remember { mutableStateOf(false) }
    var apiRoot by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val valid = isValidPlayerName(name)
    LaunchedEffect(snapshot) { if (!busy && error == null) { name = ""; editing = null; deleting = null } }
    LaunchedEffect(externalAccountName) {
        if (externalAccountName != null) {
            password = ""
            authlibLoginOpen = false
        }
    }
    LaunchedEffect(advancedMode) {
        if (!advancedMode) {
            authlibLoginOpen = false
            password = ""
        }
    }
    Modal(onDismiss) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                T("Профили", 24, C.text1, FontWeight.Bold, lineHeight = 29)
                Box(Modifier.size(28.dp).clip(CircleShape).background(C.fill).clickNoRipple(onDismiss), contentAlignment = Alignment.Center) {
                    T("✕", 14, C.text2, FontWeight.Medium, lineHeight = 17)
                }
            }
            T("Выберите офлайн-профиль для игры или добавьте новый.", 15, C.text2, lineHeight = 20)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (snapshot == null && !busy) {
                    T("Не удалось загрузить профили", 16, C.text1, FontWeight.Medium, lineHeight = 20)
                    T("Повторить", 14, C.accent, lineHeight = 18, modifier = Modifier.clickNoRipple(onRetry))
                }
                snapshot?.profiles?.forEach { profile ->
                    val active = profile.id == snapshot.activeId
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (active) C.accentSoft else C.input).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        BuildArtwork(profile.name, if (active) C.accent else C.slate, Modifier.size(40.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            T(profile.name, 16, C.text1, FontWeight.Medium, lineHeight = 20)
                            T(if (active) "Активный, офлайн" else "Офлайн-профиль", 12, C.text2, lineHeight = 15)
                        }
                        // Hidden links keep their space, so "Изменить" and "Удалить" stay in place when the active profile changes.
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            T("Выбрать", 13, C.accent, FontWeight.Medium, lineHeight = 16,
                                modifier = if (active) Modifier.alpha(0f) else Modifier.clickNoRipple { if (!busy) onSelect(profile.id) })
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                T("Изменить", 12, C.accent, lineHeight = 15, modifier = Modifier.clickNoRipple { if (!busy) { editing = profile; name = profile.name; deleting = null } })
                                T("Удалить", 12, C.danger, lineHeight = 15,
                                    modifier = if (snapshot.profiles.size > 1) Modifier.clickNoRipple { if (!busy) deleting = profile } else Modifier.alpha(0f))
                            }
                        }
                    }
                }
            }
            deleting?.let { profile ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.input).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    T("Удалить профиль «${profile.name}»? Сборки и миры сохранятся.", 14, C.text1, lineHeight = 20)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        T("Отмена", 14, C.text2, lineHeight = 18, modifier = Modifier.clickNoRipple { deleting = null })
                        T("Удалить профиль", 14, C.danger, FontWeight.Medium, lineHeight = 18, modifier = Modifier.clickNoRipple { if (!busy) onDelete(profile.id) })
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.input).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    externalAccountName != null -> {
                        T("Authlib Injector: ${externalAccountName}", 14, C.text1, FontWeight.Medium, lineHeight = 18)
                        T("Выйти", 13, C.danger, lineHeight = 16, modifier = Modifier.clickNoRipple { if (!busy) onSignOutExternal() })
                    }
                    authlibLoginOpen -> {
                        T("Вход через Authlib Injector", 15, C.text1, FontWeight.Medium, lineHeight = 18)
                        SearchBox(apiRoot, { apiRoot = it }, "API сервера, например https://auth.example/api", Modifier.fillMaxWidth(), height = 44.dp)
                        SearchBox(username, { username = it }, "Логин или почта", Modifier.fillMaxWidth(), height = 44.dp)
                        SearchBox(password, { password = it }, "Пароль", Modifier.fillMaxWidth(), height = 44.dp, obscureText = true)
                        T("Пароль и токен остаются только в памяти лаунчера.", 12, C.text2, lineHeight = 15)
                    }
                    editing == null -> {
                        T("Новый профиль", 15, C.text1, FontWeight.Medium, lineHeight = 18)
                        T("Войти через Microsoft", 14, C.accent, FontWeight.Medium, lineHeight = 18,
                            modifier = Modifier.clickNoRipple { microsoftUnavailable = true })
                        if (microsoftUnavailable) T("Пока недоступно в бете", 12, C.text2, lineHeight = 15)
                        if (advancedMode) T("Войти через Authlib Injector", 14, C.accent, FontWeight.Medium, lineHeight = 18,
                            modifier = Modifier.clickNoRipple { authlibLoginOpen = true; microsoftUnavailable = false })
                        SearchBox(name, { name = it }, "Имя игрока", Modifier.fillMaxWidth(), height = 44.dp)
                        T("3–16 символов: A–Z, a–z, 0–9 и _", 12, C.text2, lineHeight = 15)
                    }
                    else -> {
                        T("Переименование профиля", 15, C.text1, FontWeight.Medium, lineHeight = 18)
                        SearchBox(name, { name = it }, "Имя игрока", Modifier.fillMaxWidth(), height = 44.dp)
                        T("3–16 символов: A–Z, a–z, 0–9 и _", 12, C.text2, lineHeight = 15)
                    }
                }
            }
            error?.let { T(it, 13, C.danger, lineHeight = 18) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (authlibLoginOpen) {
                    T("Назад", 14, C.text2, lineHeight = 18, modifier = Modifier.clickNoRipple { if (!busy) authlibLoginOpen = false })
                    val canSignIn = apiRoot.startsWith("https://") && username.isNotBlank() && password.isNotEmpty() && !busy
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (canSignIn) C.accentFill else C.fill).clickNoRipple {
                        if (canSignIn) onSignInExternal(apiRoot.trim(), username.trim(), password)
                    }.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        T(if (busy) "Вход…" else "Войти", 15, if (canSignIn) C.onAccent else C.text2, FontWeight.Medium, lineHeight = 18)
                    }
                } else {
                    if (editing != null) T("Отмена", 14, C.text2, lineHeight = 18, modifier = Modifier.clickNoRipple { editing = null; name = "" })
                    if (externalAccountName == null) Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (valid && !busy && snapshot != null) C.accentFill else C.fill).clickNoRipple {
                        if (valid && !busy && snapshot != null) { editing?.let { onRename(it.id, name) } ?: onAdd(name) }
                    }.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        T(if (busy) "Сохранение…" else if (editing == null) "Добавить профиль" else "Сохранить", 15, if (valid && !busy) C.onAccent else C.text2, FontWeight.Medium, lineHeight = 18)
                    }
                }
            }
        }
    }
}
