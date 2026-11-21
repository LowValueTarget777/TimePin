package org.timepin.app

import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

internal fun MainActivity.editPersonalModel() {
    work(fn={ Vault(this).get("model") ?: JSONObject() }) { active ->
        val presets = JSONArray(assets.open("providers.json").bufferedReader().use { it.readText() }).objects()
        val saved = active.optJSONArray("profiles")?.objects()?.toMutableList() ?: mutableListOf()
        if (saved.isEmpty() && active.has("url")) saved.add(obj("label" to active.optString("label","我的模型"),"url" to active.getString("url"),"name" to active.getString("name"),"key" to active.optString("key")))
        val choices = saved + presets + listOf(obj("label" to "＋ 自定义兼容接口"))
        val box = padded()
        val picker = calendarSpinner()
        picker.adapter=ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices.map { it.getString("label") })
        val label=field("配置名称"); val url=field("兼容接口地址"); val model=field("模型编号"); val key=field("API Key","",true)
        listOf(picker,label,url,model,key).forEach(box::addView)
        picker.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?) {}
            override fun onItemSelected(parent:AdapterView<*>?, v:View?, index:Int, id:Long) {
                val cfg=choices[index]
                label.setText(if(index==choices.lastIndex) "" else cfg.optString("label"))
                url.setText(cfg.optString("url"));model.setText(cfg.optString("name"));key.setText(cfg.optString("key"))
            }
        }
        picker.setSelection(saved.indexOfFirst { it.optString("url")==active.optString("url") && it.optString("name")==active.optString("name") }.coerceAtLeast(0))
        form("个人模型",box,"保存并使用") { dialog ->
            val title=label.text.toString().trim()
            val endpoint=baseUrl(url.text.toString())
            val name=model.text.toString().trim()
            require(title.isNotEmpty() && name.isNotEmpty()) {"请填写配置名称和模型编号"}
            val prev=choices[picker.selectedItemPosition]
            require(prev.optString("url")==endpoint || key.text.isEmpty() || key.text.toString()!=prev.optString("key")) {"更换地址后请重新设置该服务的密钥"}
            val cfg=obj("label" to title,"url" to endpoint,"name" to name,"key" to key.text.toString())
            val profiles=saved.filter { it.optString("label")!=title } + cfg
            val updated=JSONObject(cfg.toString()).put("profiles",JSONArray(profiles))
            work(fn={Vault(this).set("model",updated)}) {prefs.edit().putBoolean("personal",true).apply();dialog.dismiss();reload();message("已在此设备使用 "+title)}
        }
    }
}
