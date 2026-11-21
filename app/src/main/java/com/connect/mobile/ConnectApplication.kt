package com.connect.mobile

import android.app.Application

class ConnectApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
