package com.example.myapplication.logic.policy

/**
 * Singleton to store global configuration like MISSION_ID and USER_ID.
 */
object ConfigManager {
    var missionId: String = ""
    var userId: String = ""
    
    // Flag to check if configuration is set
    var isConfigured: Boolean = false
}
