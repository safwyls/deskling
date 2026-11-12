package app.deskling.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.deskling.data.CompanionRecord
import app.deskling.data.DialInfo
import app.deskling.sync.Sync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A companion shared from another phone, waiting for a yes (from a link the app was opened with). */
object Inbox { val incoming = MutableStateFlow<CompanionRecord?>(null) }

/** The pet tab while the companion is somewhere else: where it is, and how to get it back. */
@Composable
fun AwayScreen(app: AppState) {
    val v by app.store.version.collectAsState()
    v.hashCode()
    val t = app.store.travel
    val name = app.store.pet.title
    val atDial = app.store.dialNamed(t.where)
    val shared = t.where == Sync.SHARED
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (shared) "$name left by a link" else "$name is with ${t.where}", color = TextC, fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        WispView(Pose(Loop.SLEEP, null), Modifier.padding(vertical = 8.dp).alpha(0.28f))
        app.toast?.let { Text(it, color = Accent, fontSize = 15.sp, textAlign = TextAlign.Center) }
        when {
            atDial != null -> {
                val st = app.store.dialState
                if (app.store.dial?.name == atDial.name && st.has("mood")) {
                    Text("${st.optString("mood")}  •  level ${st.optInt("level")}  •  ${st.optInt("happy")}% happy", color = Dim, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                }
                Text("It lives on the dial for now: reminders come from there. Bring it along when you leave, and it carries on on this phone.",
                    color = Dim, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    busy = true
                    app.scope.launch {
                        when (val m = Sync.bringHome(app.ctx, atDial)) {
                            Sync.Move.Ok -> app.say("$name is with you", 3000)
                            is Sync.Move.Failed -> app.say(m.why, 7000)
                            else -> {}
                        }
                        busy = false
                    }
                }) { Text(if (busy) "Asking ${atDial.name}..." else "Bring $name with me") }
            }
            shared -> {
                Text("It moves in on the phone that opens the link. Until then you can send the link again, or take $name back.",
                    color = Dim, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                val link = app.store.lastShare
                if (link.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { shareText(app, name, link) }, modifier = Modifier.weight(1f)) { Text("Send the link") }
                    OutlinedButton(onClick = {
                        CompanionRecord.fromLink(link)?.let { if (Sync.takeShared(app.ctx, it) == Sync.Arrival.Ok) app.say("$name is back", 3000) }
                    }, modifier = Modifier.weight(1f)) { Text("Take it back") }
                }
            }
            else -> Text("To get it back, ask for it to be shared with this phone. If ${t.where} is a dial, pair it in Settings and bring $name home from there.",
                color = Dim, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

/** On the pet tab while the companion is here: take it to a dial, or to another phone. */
@Composable
fun TravelSection(app: AppState) {
    val name = app.store.pet.title
    val dials = app.store.dials
    var share by remember { mutableStateOf(false) }
    var occupied by remember { mutableStateOf<Pair<DialInfo, Sync.Move.Occupied>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    fun send(d: DialInfo, replace: Boolean = false) {
        busy = d.name
        app.scope.launch {
            when (val m = Sync.sendTo(app.ctx, d, replace)) {
                Sync.Move.Ok -> app.say("$name is at ${d.name}", 3000)
                is Sync.Move.Occupied -> occupied = d to m
                is Sync.Move.Failed -> app.say(m.why, 7000)
            }
            busy = null
        }
    }
    Spacer(Modifier.height(20.dp))
    Text("Take $name along", color = Dim, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Text("$name lives on one device at a time. Leave it at a dial and it carries on there; bring it back to the phone when you go.",
            color = Dim, fontSize = 13.sp)
        for (d in dials) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(enabled = busy == null, onClick = { send(d) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy == d.name) "Moving in..." else "Leave $name at ${d.name}")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(enabled = busy == null, onClick = { share = true }, modifier = Modifier.fillMaxWidth()) { Text("Give $name to another phone") }
    }
    if (share) AlertDialog(
        onDismissRequest = { share = false },
        title = { Text("Give $name to another phone?") },
        text = { Text("$name leaves this phone now, as a link. Send it to the other phone: $name moves in on the phone that opens it, " +
            "with its level, streak and today's breaks. Until then you can take it back from the pet tab.") },
        confirmButton = { TextButton(onClick = {
            share = false
            Sync.shareOut(app.ctx)?.let { shareText(app, name, it) }
        }) { Text("Make the link") } },
        dismissButton = { TextButton(onClick = { share = false }) { Text("Cancel") } },
    )
    occupied?.let { (d, m) ->
        val theirs = m.pet?.title ?: "a companion"
        AlertDialog(
            onDismissRequest = { occupied = null },
            title = { Text("${d.name} has its own $theirs") },
            text = { Text("$theirs (level ${m.level}) lives on that dial. Moving $name in replaces it, and its progress is gone for good.") },
            confirmButton = { TextButton(onClick = { occupied = null; send(d, replace = true) }) { Text("Replace it") } },
            dismissButton = { TextButton(onClick = { occupied = null }) { Text("Keep it") } },
        )
    }
}

/** A shared companion arriving: what it is, and a yes or no. */
@Composable
fun IncomingDialog(app: AppState) {
    val rec by Inbox.incoming.collectAsState()
    val r = rec ?: return
    val t = app.store.travel
    val other = t.here && t.cid != r.cid
    val level = r.care.optLong("xp").let { xp -> var l = 1; var need = 100L; var x = xp; while (x >= need) { x -= need; l++; need += 100 }; l }
    AlertDialog(
        onDismissRequest = { Inbox.incoming.value = null },
        title = { Text("Take in ${r.pet.title}?") },
        text = {
            Text("${r.pet.title} (level $level) is coming to this phone." +
                if (other) " ${app.store.pet.title} lives here now (level ${app.store.engine.level()}): taking ${r.pet.title} in replaces it for good." else "")
        },
        confirmButton = { TextButton(onClick = {
            Inbox.incoming.value = null
            when (Sync.takeShared(app.ctx, r, replace = true)) {
                Sync.Arrival.Ok -> app.say("${r.pet.title} is with you", 3000)
                Sync.Arrival.Stale -> app.say("That link is out of date: ${r.pet.title} has moved since", 6000)
                else -> {}
            }
        }) { Text(if (other) "Replace" else "Take in") } },
        dismissButton = { TextButton(onClick = { Inbox.incoming.value = null }) { Text("Not now") } },
    )
}

fun shareText(app: AppState, name: String, link: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "$name is coming to you")
        .putExtra(Intent.EXTRA_TEXT, "$name is coming to you. Open this on your phone, with the Deskling app installed:\n$link")
    app.ctx.startActivity(Intent.createChooser(i, "Send $name").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
