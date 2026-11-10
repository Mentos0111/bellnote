package com.bellnote.util

import android.content.Intent
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.bellnote.ui.MainActivity

/**
 * 子页面（编辑 / 截图识别 / 设置 / 对账 / 权限说明）统一用这套导航：
 * 左上角一个箭头直接回主页，而不是让用户一层层按返回。
 */

/** 一步回主页：清掉中间堆叠的页面，不留副本。 */
fun AppCompatActivity.goHome() {
    startActivity(
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    )
    finish()
}

/** 在标题栏左侧显示回主页箭头。 */
fun AppCompatActivity.enableHomeUp() {
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    supportActionBar?.setDisplayShowHomeEnabled(true)
}

/** 箭头被点中时返回 true，各页面在 onOptionsItemSelected 里先问一句就行。 */
fun AppCompatActivity.handleHomeUp(item: MenuItem): Boolean {
    if (item.itemId == android.R.id.home) {
        goHome()
        return true
    }
    return false
}
