package com.vabugeode.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.data.ProtocolDeviceRepository
import com.vabugeode.app.model.LinkState
import com.vabugeode.app.protocol.HikeStateMsg
import com.vabugeode.app.ui.Fmt
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun HikeControls(repo: ProtocolDeviceRepository) {
    val state by repo.lifecycle.collectAsStateWithLifecycle()
    val confirmed by repo.lifecycleFresh.collectAsStateWithLifecycle()
    val fresh by repo.statusFresh.collectAsStateWithLifecycle()
    val link by repo.link.collectAsStateWithLifecycle()
    val busy by repo.hikeBusy.collectAsStateWithLifecycle()
    val pending by repo.lifecyclePending.collectAsStateWithLifecycle()
    val stats by repo.stats.collectAsStateWithLifecycle()
    val info by repo.info.collectAsStateWithLifecycle()
    var action by remember(state?.hike,state?.revision) { mutableStateOf<Int?>(null) }
    val scope=rememberCoroutineScope()
    val enabled=confirmed && fresh && link==LinkState.CONNECTED && !busy && !pending && !stats.syncing
    val phase=state?.phase
    Panel {
        Text(when (phase) {
            HikeStateMsg.IDLE -> "Готов к походу"
            HikeStateMsg.ACTIVE -> if (confirmed && fresh) "Поход идёт" else "Завершение не подтверждено"
            HikeStateMsg.RECOVERY -> "Незавершённый поход"
            HikeStateMsg.ENDED -> if (confirmed) "Поход завершён" else "Последний подтверждённый поход завершён"
            HikeStateMsg.ERROR -> "Не удалось прочитать состояние похода"
            else -> "Состояние похода"
        }, style=MaterialTheme.typography.titleMedium, color=Palette.Text)
        Spacer(Modifier.height(8.dp))
        Text(when {
            busy -> "Сохраняем. Ожидаем подтверждения устройства…"
            pending -> "Результат команды неизвестен. Проверьте состояние после подключения. Автоматического повтора не будет."
            phase==HikeStateMsg.ENDED -> "Запись остановлена. Новый поход начнётся только по вашему действию. Доступная копия — во вкладке «Походы»; полнота загрузки проверяется отдельно."
            phase==HikeStateMsg.RECOVERY -> "Запись прерывалась. Продолжите тот же поход или завершите его."
            phase==HikeStateMsg.IDLE -> "Устройство включено, запись ещё не начата."
            phase==HikeStateMsg.ERROR -> "Проверьте память устройства. Новый поход не создаётся поверх неизвестного состояния."
            info!=null && !repo.supportsLifecycle() -> "Для отдельного начала и завершения нужна прошивка 0.1.21 или новее."
            !confirmed -> "Подключитесь и дождитесь актуального состояния устройства."
            else -> "Ночью завершать поход не нужно: прибор сам переходит в режим привала."
        }, style=MaterialTheme.typography.bodyMedium, color=Palette.TextDim)
        state?.endedAt?.takeIf { it>0 && phase==HikeStateMsg.ENDED }?.let {
            Text("Завершён: ${Fmt.dateTime(it*1000)}", color=Palette.TextDim)
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if (phase==HikeStateMsg.IDLE || phase==HikeStateMsg.ENDED)
                TextButton(enabled=enabled,onClick={action=HikeStateMsg.START}) { Text("Начать поход") }
            if (phase==HikeStateMsg.RECOVERY)
                TextButton(enabled=enabled,onClick={action=HikeStateMsg.RESUME}) { Text("Продолжить поход") }
            if (phase==HikeStateMsg.ACTIVE || phase==HikeStateMsg.RECOVERY)
                TextButton(enabled=enabled,onClick={action=HikeStateMsg.FINISH}) { Text("Завершить поход") }
        }
        if (pending && link==LinkState.CONNECTED && !busy)
            TextButton(onClick={repo.refreshInfo()}) { Text("Проверить результат") }
    }
    action?.let { chosen ->
        AlertDialog(onDismissRequest={action=null},
            title={ Text(when(chosen) {
                HikeStateMsg.START -> "Начать поход?"
                HikeStateMsg.RESUME -> "Продолжить прежний поход?"
                else -> "Завершить поход?"
            }) },
            text={ Text(if (chosen==HikeStateMsg.FINISH)
                "Запись остановится, данные сохранятся. Следующий поход автоматически не начнётся."
                else "Устройство проверит сохранение на SD и подтвердит начало записи.") },
            confirmButton={ TextButton(enabled=enabled,onClick={
                val expected=state
                action=null
                scope.launch { repo.changeHike(chosen,expected) }
            }) { Text("Подтвердить") } },
            dismissButton={ TextButton(onClick={action=null}) { Text("Отмена") } },
        )
    }
}
