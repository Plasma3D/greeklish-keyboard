package com.greeklish.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import java.io.File;

/**
 * Keyboard colours. Theme 0 follows the wallpaper (Material You, Android 12+) and the
 * phone's light/dark mode; 1-6 are fixed palettes; 7 is the user's own photo.
 */
public final class Theme {
    public static final int WALLPAPER = 0, LIGHT = 1, DARK = 2, BLACK = 3, BLUE = 4, ROSE = 5, GREEN = 6, PHOTO = 7;
    public static final String[] NAMES = {"Wallpaper colours", "Light", "Dark", "Black", "Blue", "Rose", "Green", "Your photo"};

    public final int id;
    public final boolean dark;
    public final boolean borders;
    public final int background, key, functionKey, keyPressed, text, textSecondary, accent, onAccent, divider, popup, border;
    /** Background photo (theme PHOTO only), already cropped for the keyboard; null otherwise. */
    public final Bitmap photo;
    /** Changes whenever anything that affects drawing changes; used to rebuild the keyboard. */
    public final String signature;

    public Theme(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("keyboard", Context.MODE_PRIVATE);
        int chosen = prefs.getInt("theme", WALLPAPER);
        borders = prefs.getBoolean("key_borders", false);
        boolean systemDark = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        Bitmap bmp = null;
        if (chosen == PHOTO) {
            File f = photoFile(ctx);
            if (f.exists()) bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp == null) chosen = WALLPAPER;
        }
        id = chosen;
        photo = bmp;
        int[] c;
        switch (chosen) {
            case LIGHT: c = new int[]{0xFFEEF0F4, 0xFFFFFFFF, 0xFFD8DCE3, 0xFFC4C9D2, 0xFF1B1B1F, 0xFF5E5E68, 0xFF0B57D0, 0xFFFFFFFF, 0xFFFFFFFF}; break;
            case DARK: c = new int[]{0xFF202124, 0xFF3C4043, 0xFF2D2E31, 0xFF5F6368, 0xFFE8EAED, 0xFF9AA0A6, 0xFF8AB4F8, 0xFF202124, 0xFF5F6368}; break;
            case BLACK: c = new int[]{0xFF000000, 0xFF1F1F1F, 0xFF141414, 0xFF3A3A3A, 0xFFEEEEEE, 0xFF999999, 0xFF8AB4F8, 0xFF000000, 0xFF3A3A3A}; break;
            case BLUE: c = new int[]{0xFF0F2140, 0xFF1E3A66, 0xFF162C4F, 0xFF2D5290, 0xFFE6EEFA, 0xFF9DB2D6, 0xFF7FB0FF, 0xFF0F2140, 0xFF2D5290}; break;
            case ROSE: c = new int[]{0xFF3A1424, 0xFF5C2340, 0xFF4A1B33, 0xFF7A3157, 0xFFFBE9F0, 0xFFD9A7BD, 0xFFF2B8C6, 0xFF4A1B28, 0xFF7A3157}; break;
            case GREEN: c = new int[]{0xFF0F2A1E, 0xFF1E4733, 0xFF173828, 0xFF2A6247, 0xFFE5F5EC, 0xFF9CC5AE, 0xFF8FD9B0, 0xFF0F2A1E, 0xFF2A6247}; break;
            case PHOTO:
                // Translucent dark layers so the photo shows through but letters stay readable.
                c = new int[]{0x59000000, 0x8C202020, 0xB3141414, 0xCC4A4A4A, 0xFFFFFFFF, 0xFFDDDDDD, 0xFF8AB4F8, 0xFF0B1E3D, 0xF0303030};
                break;
            default: c = wallpaper(ctx, systemDark); break;
        }
        background = c[0];
        key = c[1];
        functionKey = c[2];
        keyPressed = c[3];
        text = c[4];
        textSecondary = c[5];
        accent = c[6];
        onAccent = c[7];
        popup = c[8];
        dark = chosen == WALLPAPER ? systemDark : chosen != LIGHT;
        divider = (textSecondary & 0x00FFFFFF) | 0x55000000;
        border = (textSecondary & 0x00FFFFFF) | 0x80000000;
        File f = photoFile(ctx);
        signature = chosen + "/" + borders + "/" + dark + "/" + (chosen == PHOTO ? f.lastModified() : 0);
    }

    public static File photoFile(Context ctx) {
        return new File(ctx.getFilesDir(), "keyboard_photo.jpg");
    }

    private static int[] wallpaper(Context ctx, boolean dark) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (dark) {
                return new int[]{c(ctx, android.R.color.system_neutral1_900), c(ctx, android.R.color.system_neutral2_700),
                        c(ctx, android.R.color.system_neutral2_800), c(ctx, android.R.color.system_neutral2_600),
                        c(ctx, android.R.color.system_neutral1_50), c(ctx, android.R.color.system_neutral2_200),
                        c(ctx, android.R.color.system_accent1_200), c(ctx, android.R.color.system_accent1_800),
                        c(ctx, android.R.color.system_neutral2_600)};
            }
            return new int[]{c(ctx, android.R.color.system_neutral1_50), c(ctx, android.R.color.system_neutral1_0),
                    c(ctx, android.R.color.system_neutral2_100), c(ctx, android.R.color.system_neutral2_200),
                    c(ctx, android.R.color.system_neutral1_900), c(ctx, android.R.color.system_neutral2_600),
                    c(ctx, android.R.color.system_accent1_600), c(ctx, android.R.color.system_accent1_0),
                    c(ctx, android.R.color.system_neutral1_0)};
        }
        return dark
                ? new int[]{0xFF1B1B1F, 0xFF46464F, 0xFF303036, 0xFF5E5E68, 0xFFE4E1E6, 0xFFC7C5D0, 0xFFA8C7FA, 0xFF062E6F, 0xFF5E5E68}
                : new int[]{0xFFEEF0F4, 0xFFFFFFFF, 0xFFD8DCE3, 0xFFC4C9D2, 0xFF1B1B1F, 0xFF5E5E68, 0xFF0B57D0, 0xFFFFFFFF, 0xFFFFFFFF};
    }

    private static int c(Context ctx, int id) {
        return ctx.getColor(id);
    }
}
