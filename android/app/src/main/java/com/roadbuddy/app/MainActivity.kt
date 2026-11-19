package com.roadbuddy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.roadbuddy.app.navigation.RoadBuddyApp
import com.roadbuddy.core.ui.theme.RoadBuddyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RoadBuddyTheme {
                RoadBuddyApp()
            }
        }
    }
}
