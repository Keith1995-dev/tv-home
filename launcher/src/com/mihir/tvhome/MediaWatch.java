package com.mihir.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing itself. Android only lets an app see which media is playing in other apps (MediaSessionManager)
 * if it has notification access, and that access is granted to a listener like this one (over ADB at install).
 */
public class MediaWatch extends NotificationListenerService { }
