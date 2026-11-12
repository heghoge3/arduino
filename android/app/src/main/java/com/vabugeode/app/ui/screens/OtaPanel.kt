package com.vabugeode.app.ui.screens
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.components.Panel
import com.vabugeode.app.ui.components.SectionLabel

@Composable fun OtaPanel(vm:AppViewModel){
 val state by vm.repo.ota.state.collectAsStateWithLifecycle()
 val link by vm.repo.link.collectAsStateWithLifecycle()
 var confirm by remember { mutableStateOf(false) }
 if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Обновить прошивку прибора?")},
  text={Text("Версия ${state.target}. Завершите поход, обеспечьте питание и оставьте приложение открытым. Прибор перезапустится; результат будет проверен после подключения.")},
  confirmButton={TextButton(onClick={confirm=false;vm.repo.ota.start()}){Text("Обновить")}},
  dismissButton={TextButton(onClick={confirm=false}){Text("Отмена")}})
 Panel {
  SectionLabel("Прошивка прибора")
  Spacer(Modifier.height(8.dp));Text(state.text)
  state.caps?.let{Text("Версия на приборе: ${it.version}")}
  if(state.busy){Spacer(Modifier.height(8.dp));LinearProgressIndicator(progress={state.progress/100f});Text("Передано ${state.progress}% — результат ещё проверяется")}
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   OutlinedButton(enabled=!state.busy&&link==com.vabugeode.app.model.LinkState.CONNECTED,onClick={vm.repo.ota.check()}){Text("Проверить")}
   if(state.canUpdate)Button(enabled=!state.busy,onClick={confirm=true}){Text("Обновить")}
  }
 }
}
