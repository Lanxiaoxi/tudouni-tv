package com.tudouni.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tudouni.tv.ui.App
import com.tudouni.tv.ui.theme.TudouniTVTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TudouniTVTheme {
                App()
            }
        }
    }
}
