package com.grandsphere.papercut.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ViewerSettings::class, PdfDocument::class, Bookmark::class, FolioItem::class],
            version = 21,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun documentDao(): DocumentDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun folioDao(): FolioDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN restoreLastPdf INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE settings ADD COLUMN lastPdfUri TEXT")
                db.execSQL("ALTER TABLE settings ADD COLUMN rememberLastZoom INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE settings ADD COLUMN startupZoomMode TEXT NOT NULL DEFAULT 'FIT_WIDTH'")
                db.execSQL("ALTER TABLE settings ADD COLUMN startupZoomPercent REAL NOT NULL DEFAULT 100")
                db.execSQL("ALTER TABLE settings ADD COLUMN lockZoom INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN lockSideScroll INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN fontScale REAL NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE settings ADD COLUMN highlightColor INTEGER NOT NULL DEFAULT ${ViewerSettings.DEFAULT_HIGHLIGHT}")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN pagePaddingDp INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN pageBorderEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN pageBorderColor INTEGER NOT NULL DEFAULT ${ViewerSettings.DEFAULT_PAGE_BORDER}")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN allowFollowLinks INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN doubleTapMenu INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE settings ADD COLUMN appFontColor INTEGER NOT NULL DEFAULT ${ViewerSettings.DEFAULT_APP_FONT}")
                db.execSQL("ALTER TABLE settings ADD COLUMN originalColors INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN twoFingerPan INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE settings SET allowFollowLinks = 1")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN lastPageIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN lastScrollXPdf REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN lastScrollYPdf REAL NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS documents (
                        uri TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        displayPath TEXT NOT NULL,
                        lastPageIndex INTEGER NOT NULL DEFAULT 0,
                        lastScrollXPdf REAL NOT NULL DEFAULT 0,
                        lastScrollYPdf REAL NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bookmarks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        documentUri TEXT NOT NULL,
                        name TEXT NOT NULL,
                        pageIndex INTEGER NOT NULL,
                        scrollXPdf REAL NOT NULL,
                        scrollYPdf REAL NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        FOREIGN KEY(documentUri) REFERENCES documents(uri) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_documentUri ON bookmarks(documentUri)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN restoreLastPosition INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN alwaysDarkMode INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN imageContrast REAL NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN imageAlpha REAL NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE settings ADD COLUMN darkImageBlend REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN darkImageContrast REAL NOT NULL DEFAULT 0.20")
                db.execSQL("ALTER TABLE settings ADD COLUMN darkImageAlpha REAL NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE settings SET darkImageAlpha = 0.20")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN allowTextSelection INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN horizontalPagePaddingDp INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN autoDisablePan INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN singleTapMenu INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN swipeFromBottomMenu INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE settings SET singleTapMenu = 1 WHERE doubleTapMenu = 0")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE settings ADD COLUMN actionColor INTEGER NOT NULL DEFAULT ${ViewerSettings.DEFAULT_ACTION}",
                )
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN openFolioOnStartup INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN folioOpenLastViewed INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE settings ADD COLUMN lastFolioItemId INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS folio_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        uri TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        displayPath TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        hidden INTEGER NOT NULL DEFAULT 0,
                        isStartItem INTEGER NOT NULL DEFAULT 0,
                        zoomMode TEXT NOT NULL DEFAULT 'FIT_WIDTH',
                        customZoomPercent REAL NOT NULL DEFAULT 100,
                        lockZoom INTEGER NOT NULL DEFAULT 0,
                        lockSideScroll INTEGER NOT NULL DEFAULT 1,
                        originalColors INTEGER NOT NULL DEFAULT 0,
                        lastPageIndex INTEGER NOT NULL DEFAULT 0,
                        lastScrollXPdf REAL NOT NULL DEFAULT 0,
                        lastScrollYPdf REAL NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_folio_items_uri ON folio_items(uri)")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN folioEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folio_items ADD COLUMN localName TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE settings SET
                        fontScale = 0.85,
                        rememberLastZoom = 0,
                        autoDisablePan = 1,
                        singleTapMenu = 1,
                        doubleTapMenu = 0,
                        swipeFromBottomMenu = 1
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE settings ADD COLUMN allowMultipleInstances INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE settings ADD COLUMN folioStoreLastPosition INTEGER NOT NULL DEFAULT 1",
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "Papercut.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                )
                .fallbackToDestructiveMigration()
                .build()
    }
}
