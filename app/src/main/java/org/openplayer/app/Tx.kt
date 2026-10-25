package org.openplayer.app

import android.content.Context
import androidx.annotation.StringRes

/**
 * Kodun her yerinden çevrilebilir metne erişim: `Tx.s(R.string.anahtar, bağımsız değişkenler)`.
 * Uygulama bağlamı [OpenPlayerApp] açılırken verilir; birim testlerinde bağlam yoktur, o durumda
 * anahtar adı döner (testler metne değil davranışa bakar).
 */
object Tx {
    @Volatile
    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun s(@StringRes id: Int, vararg args: Any?): String {
        val c = app ?: return "#$id"
        return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
    }
}
