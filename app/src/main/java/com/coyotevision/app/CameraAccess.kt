package com.coyotevision.app

/** Camera intent is local to this screen lifetime. Permission alone never grants intent. */
internal class CameraAccess {
    var requested = false
        private set
    var permissionPending = false
        private set
    var foreground = false
        private set
    var generation = 0L
        private set
    val canStart get() = requested && foreground && !permissionPending

    fun enterForeground() { foreground = true }
    fun request(hasPermission: Boolean) {
        if (!foreground || permissionPending) return
        requested = true; permissionPending = !hasPermission; generation++
    }
    fun permissionResult(granted: Boolean) {
        if (!requested || !permissionPending) return
        permissionPending = false
        if (!granted) close()
    }
    fun close() { requested = false; permissionPending = false; generation++ }
    fun leaveForeground() {
        foreground = false
        // A permission prompt can pause the activity. Preserve only that explicit pending request.
        if (!permissionPending) close()
    }
    fun accepts(ticket: Long) = canStart && ticket == generation
}
