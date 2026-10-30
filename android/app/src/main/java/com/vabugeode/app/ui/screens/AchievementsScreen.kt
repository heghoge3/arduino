package com.vabugeode.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vabugeode.app.achievements.*
import com.vabugeode.app.data.HikeSummary
import com.vabugeode.app.ui.AppViewModel
import com.vabugeode.app.ui.components.*
import com.vabugeode.app.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun Rarity.color():Color=when(this){
    Rarity.COMMON->Color(0xFFA8ADB5);Rarity.UNCOMMON->Color(0xFF57D68D);Rarity.RARE->Color(0xFFFF7866)
    Rarity.EPIC->Color(0xFFC080FF);Rarity.LEGENDARY->Color(0xFFFFC247)
}
private fun BadgeGroup.icon()=when(this){
    BadgeGroup.ROUTE->Icons.Default.Route;BadgeGroup.STEPS->Icons.Default.DirectionsWalk
    BadgeGroup.JOURNAL->Icons.Default.MenuBook;BadgeGroup.TIME->Icons.Default.Schedule
    BadgeGroup.CALENDAR->Icons.Default.CalendarMonth;BadgeGroup.LIGHT->Icons.Default.WbTwilight
    BadgeGroup.GEO->Icons.Default.Explore;BadgeGroup.WEATHER->Icons.Default.Sensors
    BadgeGroup.MARKS->Icons.Default.Bookmark;BadgeGroup.SIGNAL->Icons.Default.SatelliteAlt
}

@Composable
fun AchievementsScreen(vm:AppViewModel,padding:PaddingValues){
    val all by vm.achievements.progress.collectAsStateWithLifecycle()
    val ready by vm.achievements.ready.collectAsStateWithLifecycle()
    AchievementCollection(all,ready,padding,vm::seeAchievement)
}

/** Ни названия, ни условия, ни частичный прогресс закрытых секретов не попадают в дерево UI. */
@Composable
internal fun AchievementCollection(all:List<AchievementProgress>,ready:Boolean,padding:PaddingValues,onRead:(String)->Unit={}){
    var filter by rememberSaveable {mutableStateOf(0)}
    val public=all.filter{!it.badge.secret}.sortedWith(compareBy({it.badge.rarity.ordinal},{AchievementCatalog.public.indexOf(it.badge)}))
    val secrets=all.filter{it.badge.secret&&it.unlocked}.sortedBy{it.badge.rarity.ordinal}
    val unlocked=all.count{it.unlocked};val secretTotal=AchievementCatalog.secretCount;val total=AchievementCatalog.total
    val context=LocalContext.current
    var permission by remember {mutableStateOf(Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permission=it}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp,padding.calculateTopPadding()+10.dp,16.dp,padding.calculateBottomPadding()+24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{
            Panel(accent=Palette.Warn,title="Полевой архив",titleRight="$total знаков"){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Icon(Icons.Default.EmojiEvents,null,tint=Palette.Warn,modifier=Modifier.size(44.dp))
                    Spacer(Modifier.width(16.dp))
                    Column{
                        Text("$unlocked / $total",fontFamily=Fonts.Pixel,fontSize=48.sp,color=Palette.Text)
                        Text("${all.count{it.new}} новых · ${public.count{it.unlocked}} открытых · ${secrets.size} секретных",style=MaterialTheme.typography.labelSmall,color=Palette.TextDim)
                    }
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress={unlocked.toFloat()/total},modifier=Modifier.fillMaxWidth().height(4.dp),color=Palette.Warn,trackColor=Palette.Outline)
                Spacer(Modifier.height(12.dp))
                Text("Учитываются только походы с включённой галочкой «Учитывать в статистике». Эмулятор не участвует.",style=MaterialTheme.typography.bodySmall,color=Palette.TextDim)
            }
        }
        if(!permission)item{
            TextButton(onClick={if(Build.VERSION.SDK_INT>=33)launcher.launch(Manifest.permission.POST_NOTIFICATIONS)}){Icon(Icons.Default.NotificationsNone,null);Spacer(Modifier.width(8.dp));Text("Разрешить уведомления")}
        }
        item{
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf("Все открытые","Получены","Впереди").forEachIndexed {i,t->FilterChip(selected=filter==i,onClick={filter=i},label={Text(t)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Palette.Geo,selectedLabelColor=Palette.Bg,labelColor=Palette.TextDim))}
            }
        }
        if(!ready)item{Text("Читаем статистику походов…",color=Palette.TextDim)}
        Rarity.entries.forEach{tier->
            val list=public.filter{it.badge.rarity==tier&&(filter==0||(filter==1&&it.unlocked)||(filter==2&&!it.unlocked))}
            if(list.isNotEmpty()){
                item(key="tier${tier.name}"){SectionLabel("${tier.title} · ${list.count{it.unlocked}} / ${list.size}",color=tier.color())}
                items(list,key={it.badge.id}){AchievementCard(it,onRead)}
            }
        }
        item(key="secret"){
            Panel(title="Секретный архив",accent=Palette.Pdr,titleRight="${secrets.size} / $secretTotal"){
                Row(verticalAlignment=Alignment.CenterVertically){
                    Icon(Icons.Default.Lock,null,tint=Palette.Pdr,modifier=Modifier.size(30.dp));Spacer(Modifier.width(12.dp))
                    Text("${secretTotal-secrets.size} тайн ещё не раскрыты",style=MaterialTheme.typography.titleMedium,color=Palette.Text)
                }
                Spacer(Modifier.height(8.dp))
                Text("Название и условие появятся только после выполнения. Продолжай исследовать.",style=MaterialTheme.typography.bodyMedium,color=Palette.TextDim)
            }
        }
        items(secrets,key={it.badge.id}){AchievementCard(it,onRead)}
        item{Text("Редкость отражает сложность условия. Время считается по часовому поясу телефона; пропуски записей не считаются движением. GPS-подмены не дают географических достижений.",style=MaterialTheme.typography.labelSmall,color=Palette.TextFaint)}
    }
}

@Composable
private fun AchievementCard(p:AchievementProgress,onRead:(String)->Unit){
    val b=p.badge;val color=b.rarity.color()
    Panel(Modifier.fillMaxWidth().clickable(enabled=p.new){onRead(b.id)},accent=if(p.unlocked)color else color.copy(alpha=.6f)){
        Row(verticalAlignment=Alignment.Top){
            Box(Modifier.size(44.dp).background(color.copy(alpha=if(p.unlocked).12f else .05f)).border(1.dp,color.copy(alpha=if(p.unlocked)1f else .6f)),contentAlignment=Alignment.Center){
                Icon(if(b.secret)Icons.Default.AutoAwesome else b.group.icon(),null,tint=color,modifier=Modifier.size(25.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)){
                Text(b.title,style=MaterialTheme.typography.titleMedium,color=if(p.unlocked)Palette.Text else Palette.TextDim)
                Text("${b.rarity.title} · ${b.group.title}${if(p.new)" · НОВОЕ" else ""}",style=MaterialTheme.typography.labelSmall,color=color)
            }
            if(p.unlocked)Icon(Icons.Default.CheckCircle,"Получено",tint=color,modifier=Modifier.size(20.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(b.condition,style=MaterialTheme.typography.bodyMedium,color=Palette.TextDim)
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(progress={p.fraction},modifier=Modifier.fillMaxWidth().height(3.dp).semantics{contentDescription="Прогресс достижения"},color=color,trackColor=Palette.Outline)
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Text(p.counter,style=MaterialTheme.typography.labelMedium,color=if(p.unlocked)color else Palette.TextDim)
            p.unlockedMs?.let{Text(DateTimeFormatter.ofPattern("dd.MM.yy").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())),style=MaterialTheme.typography.labelSmall,color=Palette.TextFaint)}
        }
    }
}

@Composable
internal fun HikeStatisticsControl(vm:AppViewModel,h:HikeSummary){
    val selected by vm.achievementHikes.collectAsStateWithLifecycle()
    val busy by vm.repo.hikeBusy.collectAsStateWithLifecycle()
    val checked=selected.any{it.device==h.device&&it.hike==h.hike}
    var confirm by remember {mutableStateOf(false)}
    val context=LocalContext.current
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){
        Checkbox(checked=checked,onCheckedChange={if(it)confirm=true},enabled=!checked&&!h.demo&&!busy)
        Column(Modifier.weight(1f)){
            Text("Учитывать в статистике",style=MaterialTheme.typography.labelMedium,color=if(checked)Palette.Good else Palette.TextDim)
            if(checked)Text("Учёт закреплён · удаление недоступно",style=MaterialTheme.typography.labelSmall,color=Palette.TextFaint)
            if(h.demo)Text("Эмулятор не участвует",style=MaterialTheme.typography.labelSmall,color=Palette.TextFaint)
        }
        if(checked)Icon(Icons.Default.Lock,"Поход защищён от удаления",tint=Palette.TextFaint,modifier=Modifier.size(16.dp))
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Добавить в статистику навсегда?")},text={Text("Поход начнёт учитываться в достижениях. Снять галочку и удалить этот поход через приложение, включая удаление с SD, после этого будет нельзя.")},confirmButton={TextButton(enabled=!busy,onClick={
        vm.includeStatistics(h);confirm=false
        val prefs=context.getSharedPreferences("achievements",0)
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("notificationAsked",false)){
            prefs.edit().putBoolean("notificationAsked",true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }){Text("Учитывать навсегда")}},dismissButton={TextButton(onClick={confirm=false}){Text("Отмена")}})
}
