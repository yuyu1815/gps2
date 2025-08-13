package com.yuzumican.andoroidgps;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.BaseColumns;

public class DatabaseHelper extends SQLiteOpenHelper {

    public static final int DATABASE_VERSION = 1;
    public static final String DATABASE_NAME = "Fingerprints.db";

    public static class LocationEntry implements BaseColumns {
        public static final String TABLE_NAME = "locations";
        public static final String COLUMN_NAME_POS_X = "pos_x";
        public static final String COLUMN_NAME_POS_Y = "pos_y";
    }

    public static class WifiReadingEntry implements BaseColumns {
        public static final String TABLE_NAME = "wifi_readings";
        public static final String COLUMN_NAME_LOCATION_ID = "location_id";
        public static final String COLUMN_NAME_BSSID = "bssid";
        public static final String COLUMN_NAME_RSSI = "rssi";
    }

    private static final String SQL_CREATE_LOCATIONS =
            "CREATE TABLE " + LocationEntry.TABLE_NAME + " (" +
                    LocationEntry._ID + " INTEGER PRIMARY KEY," +
                    LocationEntry.COLUMN_NAME_POS_X + " REAL," +
                    LocationEntry.COLUMN_NAME_POS_Y + " REAL)";

    private static final String SQL_CREATE_WIFI_READINGS =
            "CREATE TABLE " + WifiReadingEntry.TABLE_NAME + " (" +
                    WifiReadingEntry._ID + " INTEGER PRIMARY KEY," +
                    WifiReadingEntry.COLUMN_NAME_LOCATION_ID + " INTEGER," +
                    WifiReadingEntry.COLUMN_NAME_BSSID + " TEXT," +
                    WifiReadingEntry.COLUMN_NAME_RSSI + " INTEGER," +
                    "FOREIGN KEY(" + WifiReadingEntry.COLUMN_NAME_LOCATION_ID + ") REFERENCES " +
                    LocationEntry.TABLE_NAME + "(" + LocationEntry._ID + "))";

    private static final String SQL_DELETE_LOCATIONS =
            "DROP TABLE IF EXISTS " + LocationEntry.TABLE_NAME;
    private static final String SQL_DELETE_WIFI_READINGS =
            "DROP TABLE IF EXISTS " + WifiReadingEntry.TABLE_NAME;


    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_LOCATIONS);
        db.execSQL(SQL_CREATE_WIFI_READINGS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // This database is only a cache for online data, so its upgrade policy is
        // to simply to discard the data and start over
        db.execSQL(SQL_DELETE_WIFI_READINGS);
        db.execSQL(SQL_DELETE_LOCATIONS);
        onCreate(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onUpgrade(db, oldVersion, newVersion);
    }
}
