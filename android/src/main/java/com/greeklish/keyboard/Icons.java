package com.greeklish.keyboard;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Outline icons drawn with Canvas, so they stay crisp at any size and match the theme. */
public final class Icons {
    public static final int SHIFT = 1, SHIFT_ON = 2, SHIFT_LOCK = 3, BACKSPACE = 4, ENTER = 5, SEND = 6,
            SEARCH = 7, NEXT = 8, DONE = 9, GLOBE = 10, EMOJI = 11, CLIPBOARD = 12, SETTINGS = 13,
            KEYBOARD = 14, TRASH = 15;

    private Icons() {
    }

    /** Draws icon {@code id} centred at (cx, cy) inside a square of side {@code s}. */
    public static void draw(Canvas c, int id, float cx, float cy, float s, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.085f);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        float h = s / 2f;
        Path path = new Path();
        switch (id) {
            case SHIFT:
            case SHIFT_ON:
            case SHIFT_LOCK: {
                path.moveTo(cx, cy - h * 0.8f);
                path.lineTo(cx + h * 0.75f, cy + h * 0.05f);
                path.lineTo(cx + h * 0.35f, cy + h * 0.05f);
                path.lineTo(cx + h * 0.35f, cy + h * 0.55f);
                path.lineTo(cx - h * 0.35f, cy + h * 0.55f);
                path.lineTo(cx - h * 0.35f, cy + h * 0.05f);
                path.lineTo(cx - h * 0.75f, cy + h * 0.05f);
                path.close();
                if (id != SHIFT) p.setStyle(Paint.Style.FILL_AND_STROKE);
                c.drawPath(path, p);
                if (id == SHIFT_LOCK) {
                    c.drawLine(cx - h * 0.35f, cy + h * 0.85f, cx + h * 0.35f, cy + h * 0.85f, p);
                }
                break;
            }
            case BACKSPACE: {
                path.moveTo(cx - h * 0.85f, cy);
                path.lineTo(cx - h * 0.4f, cy - h * 0.6f);
                path.lineTo(cx + h * 0.85f, cy - h * 0.6f);
                path.lineTo(cx + h * 0.85f, cy + h * 0.6f);
                path.lineTo(cx - h * 0.4f, cy + h * 0.6f);
                path.close();
                c.drawPath(path, p);
                float x0 = cx + h * 0.22f, d = h * 0.25f;
                c.drawLine(x0 - d, cy - d, x0 + d, cy + d, p);
                c.drawLine(x0 - d, cy + d, x0 + d, cy - d, p);
                break;
            }
            case ENTER: {
                path.moveTo(cx + h * 0.7f, cy - h * 0.6f);
                path.lineTo(cx + h * 0.7f, cy + h * 0.15f);
                path.lineTo(cx - h * 0.7f, cy + h * 0.15f);
                c.drawPath(path, p);
                c.drawLine(cx - h * 0.7f, cy + h * 0.15f, cx - h * 0.3f, cy - h * 0.25f, p);
                c.drawLine(cx - h * 0.7f, cy + h * 0.15f, cx - h * 0.3f, cy + h * 0.55f, p);
                break;
            }
            case SEND: {
                path.moveTo(cx - h * 0.75f, cy - h * 0.7f);
                path.lineTo(cx + h * 0.8f, cy);
                path.lineTo(cx - h * 0.75f, cy + h * 0.7f);
                path.lineTo(cx - h * 0.5f, cy);
                path.close();
                p.setStyle(Paint.Style.FILL_AND_STROKE);
                c.drawPath(path, p);
                break;
            }
            case SEARCH: {
                c.drawCircle(cx - h * 0.15f, cy - h * 0.15f, h * 0.45f, p);
                c.drawLine(cx + h * 0.18f, cy + h * 0.18f, cx + h * 0.7f, cy + h * 0.7f, p);
                break;
            }
            case NEXT: {
                c.drawLine(cx - h * 0.7f, cy, cx + h * 0.7f, cy, p);
                c.drawLine(cx + h * 0.7f, cy, cx + h * 0.2f, cy - h * 0.5f, p);
                c.drawLine(cx + h * 0.7f, cy, cx + h * 0.2f, cy + h * 0.5f, p);
                break;
            }
            case DONE: {
                path.moveTo(cx - h * 0.7f, cy);
                path.lineTo(cx - h * 0.2f, cy + h * 0.5f);
                path.lineTo(cx + h * 0.75f, cy - h * 0.5f);
                c.drawPath(path, p);
                break;
            }
            case GLOBE: {
                float r = h * 0.75f;
                c.drawCircle(cx, cy, r, p);
                c.drawOval(new RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r), p);
                c.drawLine(cx - r, cy, cx + r, cy, p);
                break;
            }
            case EMOJI: {
                float r = h * 0.75f;
                c.drawCircle(cx, cy, r, p);
                c.drawArc(new RectF(cx - r * 0.5f, cy - r * 0.3f, cx + r * 0.5f, cy + r * 0.55f), 20, 140, false, p);
                Paint dot = new Paint(p);
                dot.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - r * 0.33f, cy - r * 0.25f, r * 0.11f, dot);
                c.drawCircle(cx + r * 0.33f, cy - r * 0.25f, r * 0.11f, dot);
                break;
            }
            case CLIPBOARD: {
                RectF body = new RectF(cx - h * 0.6f, cy - h * 0.6f, cx + h * 0.6f, cy + h * 0.8f);
                c.drawRoundRect(body, h * 0.12f, h * 0.12f, p);
                c.drawRoundRect(new RectF(cx - h * 0.3f, cy - h * 0.8f, cx + h * 0.3f, cy - h * 0.45f), h * 0.08f, h * 0.08f, p);
                c.drawLine(cx - h * 0.3f, cy, cx + h * 0.3f, cy, p);
                c.drawLine(cx - h * 0.3f, cy + h * 0.35f, cx + h * 0.15f, cy + h * 0.35f, p);
                break;
            }
            case SETTINGS: {
                float r = h * 0.48f;
                c.drawCircle(cx, cy, r, p);
                c.drawCircle(cx, cy, r * 0.4f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float x1 = cx + (float) Math.cos(a) * r, y1 = cy + (float) Math.sin(a) * r;
                    float x2 = cx + (float) Math.cos(a) * h * 0.8f, y2 = cy + (float) Math.sin(a) * h * 0.8f;
                    c.drawLine(x1, y1, x2, y2, p);
                }
                break;
            }
            case KEYBOARD: {
                c.drawRoundRect(new RectF(cx - h * 0.85f, cy - h * 0.55f, cx + h * 0.85f, cy + h * 0.55f), h * 0.12f, h * 0.12f, p);
                Paint dot = new Paint(p);
                dot.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 4; i++) {
                    c.drawCircle(cx - h * 0.5f + i * h * 0.33f, cy - h * 0.2f, h * 0.06f, dot);
                }
                c.drawLine(cx - h * 0.35f, cy + h * 0.22f, cx + h * 0.35f, cy + h * 0.22f, p);
                break;
            }
            case TRASH: {
                c.drawLine(cx - h * 0.7f, cy - h * 0.5f, cx + h * 0.7f, cy - h * 0.5f, p);
                c.drawLine(cx - h * 0.2f, cy - h * 0.75f, cx + h * 0.2f, cy - h * 0.75f, p);
                path.moveTo(cx - h * 0.5f, cy - h * 0.5f);
                path.lineTo(cx - h * 0.4f, cy + h * 0.75f);
                path.lineTo(cx + h * 0.4f, cy + h * 0.75f);
                path.lineTo(cx + h * 0.5f, cy - h * 0.5f);
                c.drawPath(path, p);
                break;
            }
            default:
                break;
        }
    }
}
