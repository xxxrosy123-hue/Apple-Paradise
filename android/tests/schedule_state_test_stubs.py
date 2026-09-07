#!/usr/bin/env python3
"""Extend Phase 4 Android adapters with only the Schedule reminder boundary."""
from pathlib import Path
import sys
root=Path(sys.argv[1])
path=root/'dev/linjian/peek/ScheduleReminder.java'
path.parent.mkdir(parents=True,exist_ok=True)
path.write_text('''package dev.linjian.peek;
import android.content.Context;
public final class ScheduleReminder {
    public static void reschedule(Context ctx) {
        if(Thread.holdsLock(ScheduleState.class)) throw new AssertionError("reminder under Schedule lock");
        ScheduleReminderTestAdapter.calls++;
    }
}
final class ScheduleReminderTestAdapter { static int calls; }
''',encoding='utf-8')
