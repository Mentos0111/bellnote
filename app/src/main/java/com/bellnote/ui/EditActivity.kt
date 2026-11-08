package com.bellnote.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.R
import com.bellnote.data.CalendarRepository
import com.bellnote.data.DEFAULT_DURATION_MINUTES
import com.bellnote.data.RepeatRule
import com.bellnote.data.SOURCE_MANUAL
import com.bellnote.data.TodoItem
import com.bellnote.data.TodoSpec
import com.bellnote.data.TodoStore
import com.bellnote.util.Permissions
import com.bellnote.util.TimeFormat
import java.util.Calendar

class EditActivity : AppCompatActivity() {

    private val repository by lazy { CalendarRepository(this) }

    private var eventId: Long = -1L
    private var eventCalendarId: Long = -1L
    private var startMillis: Long = 0L

    private lateinit var etTitle: EditText
    private lateinit var etDuration: EditText
    private lateinit var etNote: EditText
    private lateinit var spRepeat: Spinner
    private lateinit var tvWhen: TextView
    private lateinit var btnDelete: Button

    private val requestCalendar = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) save()
        else toast("没有日历权限，写不进系统日历")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        etTitle = findViewById(R.id.etTitle)
        etDuration = findViewById(R.id.etDuration)
        etNote = findViewById(R.id.etNote)
        spRepeat = findViewById(R.id.spRepeat)
        tvWhen = findViewById(R.id.tvWhen)
        btnDelete = findViewById(R.id.btnDelete)

        spRepeat.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            RepeatRule.labels()
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        startMillis = if (intent.hasExtra(EXTRA_TIME)) {
            intent.getLongExtra(EXTRA_TIME, defaultStart())
        } else {
            defaultStart()
        }

        if (eventId > 0) {
            title = getString(R.string.title_edit_old)
            btnDelete.visibility = View.VISIBLE
            etTitle.setText(intent.getStringExtra(EXTRA_TITLE).orEmpty())
            etNote.setText(intent.getStringExtra(EXTRA_NOTE).orEmpty())
            etDuration.setText(intent.getIntExtra(EXTRA_DURATION, DEFAULT_DURATION_MINUTES).toString())
            spRepeat.setSelection(RepeatRule.fromRaw(intent.getStringExtra(EXTRA_REPEAT)).ordinal)
            loadEvent(eventId)
        } else {
            title = getString(R.string.title_edit_new)
            etTitle.setText(intent.getStringExtra(EXTRA_TITLE).orEmpty())
            etNote.setText(intent.getStringExtra(EXTRA_NOTE).orEmpty())
            etDuration.setText(intent.getIntExtra(EXTRA_DURATION, DEFAULT_DURATION_MINUTES).toString())
            spRepeat.setSelection(RepeatRule.fromRaw(intent.getStringExtra(EXTRA_REPEAT)).ordinal)
        }
        updateWhenLabel()

        findViewById<Button>(R.id.btnDate).setOnClickListener { pickDate() }
        findViewById<Button>(R.id.btnTime).setOnClickListener { pickTime() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun loadEvent(id: Long) {
        Thread {
            val item = repository.loadEvent(id)
            runOnUiThread {
                if (item == null) {
                    toast("这条事件已不在日历里")
                    return@runOnUiThread
                }
                eventCalendarId = item.calendarId
                startMillis = item.startMillis
                etTitle.setText(item.title)
                etNote.setText(item.note.orEmpty())
                etDuration.setText(item.durationMinutes.toString())
                spRepeat.setSelection(item.repeat.ordinal)
                updateWhenLabel()
            }
        }.start()
    }

    private fun defaultStart(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.HOUR_OF_DAY, 1)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun updateWhenLabel() {
        tvWhen.text = TimeFormat.full(startMillis)
    }

    private fun pickDate() {
        val calendar = Calendar.getInstance().apply { timeInMillis = startMillis }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val updated = Calendar.getInstance().apply { timeInMillis = startMillis }
                updated.set(Calendar.YEAR, year)
                updated.set(Calendar.MONTH, month)
                updated.set(Calendar.DAY_OF_MONTH, day)
                startMillis = updated.timeInMillis
                updateWhenLabel()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun pickTime() {
        val calendar = Calendar.getInstance().apply { timeInMillis = startMillis }
        TimePickerDialog(
            this,
            { _, hour, minute ->
                val updated = Calendar.getInstance().apply { timeInMillis = startMillis }
                updated.set(Calendar.HOUR_OF_DAY, hour)
                updated.set(Calendar.MINUTE, minute)
                updated.set(Calendar.SECOND, 0)
                startMillis = updated.timeInMillis
                updateWhenLabel()
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun save() {
        val titleText = etTitle.text.toString().trim()
        if (titleText.isEmpty()) {
            toast("标题不能为空")
            return
        }
        val duration = etDuration.text.toString().trim().toIntOrNull() ?: 60
        val spec = TodoSpec(
            title = titleText,
            startMillis = startMillis,
            durationMinutes = duration.coerceIn(DEFAULT_DURATION_MINUTES, 24 * 60),
            note = etNote.text.toString().trim().ifBlank { null },
            repeat = RepeatRule.fromIndex(spRepeat.selectedItemPosition)
        )

        if (!Permissions.hasCalendar(this)) {
            requestCalendar.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
            return
        }

        val targetCalendar = when {
            eventId > 0 && eventCalendarId > 0 -> eventCalendarId
            else -> repository.defaultCalendarId()
        }
        if (targetCalendar <= 0) {
            toast("没有可写入的日历，请先到设置里选一个")
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        Thread {
            val store = TodoStore(this)
            val ok: Boolean
            if (eventId > 0) {
                ok = repository.updateEvent(eventId, spec, targetCalendar)
                if (ok) store.updateByEventId(eventId, spec)
            } else {
                val newId = repository.insertEvent(spec, targetCalendar)
                if (newId > 0) store.record(spec, newId, SOURCE_MANUAL)
                ok = newId > 0
            }
            runOnUiThread {
                if (ok) {
                    toast(if (eventId > 0) "已更新" else "已写入日历，双提醒已设置")
                    finish()
                } else {
                    toast("写入失败：换个目标日历再试")
                }
            }
        }.start()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("删除这条待办")
            .setMessage("日历里的这条事件和它的提醒会一起删除。")
            .setPositiveButton("删除") { _, _ ->
                val id = eventId
                if (id <= 0) return@setPositiveButton
                Thread {
                    val ok = repository.deleteEvent(id)
                    if (ok) TodoStore(this).markDeletedByEventIds(listOf(id))
                    runOnUiThread {
                        toast(if (ok) "已删除" else "删除失败")
                        if (ok) finish()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TIME = "time"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_NOTE = "note"
        const val EXTRA_REPEAT = "repeat"

        fun intentForItem(context: Context, item: TodoItem): Intent =
            Intent(context, EditActivity::class.java).apply {
                putExtra(EXTRA_EVENT_ID, item.id)
                putExtra(EXTRA_TITLE, item.title)
                putExtra(EXTRA_TIME, item.startMillis)
                putExtra(EXTRA_DURATION, item.durationMinutes)
                putExtra(EXTRA_NOTE, item.note)
                putExtra(EXTRA_REPEAT, item.repeat.name)
            }

        fun intentForSpec(context: Context, spec: TodoSpec): Intent =
            Intent(context, EditActivity::class.java).apply {
                putExtra(EXTRA_TITLE, spec.title)
                putExtra(EXTRA_TIME, spec.startMillis)
                putExtra(EXTRA_DURATION, spec.durationMinutes)
                putExtra(EXTRA_NOTE, spec.note)
                putExtra(EXTRA_REPEAT, spec.repeat.name)
            }
    }
}
