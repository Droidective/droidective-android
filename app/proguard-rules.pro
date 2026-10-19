# App-specific R8 rules. Library modules ship their own consumer rules.

# Glance builds each ActionCallback by reflection, from the class name it stored in the widget's
# PendingIntent, through its no-argument constructor. R8 can't see that call, so without this it
# strips the constructor and every widget tap fails with NoSuchMethodException in release builds.
# scripts/check-r8-keeps.sh fails the build if one is stripped again.
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}
