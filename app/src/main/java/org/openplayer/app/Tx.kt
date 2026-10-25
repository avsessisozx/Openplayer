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

    /** Yalnızca birim testleri için: metinleri bağlamsız çözer. */
    @Volatile
    var testResolver: ((Int, Array<out Any?>) -> String)? = null

    fun s(@StringRes id: Int, vararg args: Any?): String {
        testResolver?.let { return it(id, args) }
        val c = app ?: return "#$id"
        return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
    }
}
