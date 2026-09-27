package app.marlboroadvance.mpvex.ui.player.xr;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLUtils;

import java.util.Arrays;
import java.util.List;

/**
 * The headset settings panel. It is drawn with Canvas into a texture that the renderer shows in
 * front of the viewer, and gets touches from the renderer as positions across it, 0..1 from its
 * top left. All calls come on the render thread. Kept in sync by hand with RetroArch XR's copy.
 */
final class XrPanel
{
  /* Mirrors PanelCommand in xr_actions.h. */
  static final int CMD_NONE = 0;
  static final int CMD_CLOSE = 1;
  static final int CMD_RECENTER = 2;
  static final int CMD_SIZE_FIRST = 16;

  /* The renderer shows the panel at 3:4. */
  static final int WIDTH = 720;
  static final int HEIGHT = 1040;

  private static final int PAD = 32;
  private static final int HEADER = 112;
  private static final int LINE = 64;
  private static final int CONTROL_LEFT = 360;
  private static final int ROW_CLOSE = -2;

  private static final int BACKGROUND = 0xff16171b;
  private static final int SURFACE = 0xff2a2c33;
  private static final int SURFACE_PRESSED = 0xff3a3d46;
  private static final int TEXT = 0xffe8e8ec;
  private static final int TEXT_DISABLED = 0xff6a6d76;
  private static final int ACCENT = 0xffffb547;
  private static final int ON_ACCENT = 0xff16171b;

  interface Listener
  {
    void onValueChanged(Row row);
  }

  static final class Row
  {
    static final int SLIDER = 0;
    static final int TOGGLE = 1;
    static final int CHOICE = 2;
    /* Buttons; the i-th fires firstCommand + i. With a label they sit on a line of their own. */
    static final int COMMANDS = 3;

    final int kind;
    final String label;
    final String[] options;
    final int min, max, step, firstCommand;
    final String unit;
    int value;
    boolean enabled = true;

    int top, height;

    private Row(int kind, String label, String[] options, int min, int max, int step,
        String unit, int firstCommand)
    {
      this.kind = kind;
      this.label = label;
      this.options = options;
      this.min = min;
      this.max = max;
      this.step = step;
      this.unit = unit;
      this.firstCommand = firstCommand;
    }

    boolean on()
    {
      return value != 0;
    }

    private boolean twoLines()
    {
      return kind == COMMANDS && label.length() > 0;
    }
  }

  static Row slider(String label, int min, int max, int step, String unit)
  {
    return new Row(Row.SLIDER, label, null, min, max, step, unit, 0);
  }

  static Row toggle(String label)
  {
    return new Row(Row.TOGGLE, label, null, 0, 1, 1, null, 0);
  }

  static Row choice(String label, String... options)
  {
    return new Row(Row.CHOICE, label, options, 0, options.length - 1, 1, null, 0);
  }

  static Row commands(String label, int firstCommand, String... names)
  {
    return new Row(Row.COMMANDS, label, names, 0, 0, 1, null, firstCommand);
  }

  private final String title;
  private final List<Row> rows;
  private final Listener listener;
  private final Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
  private final Canvas canvas = new Canvas(bitmap);
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private boolean dirty = true;
  private boolean down;
  private int activeRow = -1;
  private int activeSegment = -1;

  XrPanel(String title, Listener listener, Row... rows)
  {
    this.title = title;
    this.listener = listener;
    this.rows = Arrays.asList(rows);
    int y = HEADER;
    for (Row row : this.rows)
    {
      row.top = y;
      row.height = row.twoLines() ? LINE * 2 - 16 : LINE;
      y += row.height + 8;
    }
  }

  /** Call after changing a row's value or enabled state from outside. */
  void invalidate()
  {
    dirty = true;
  }

  /** Redraws into the bound GL context's texture if anything changed. */
  boolean upload(int texture)
  {
    if (!dirty)
      return false;
    draw();
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
    GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    dirty = false;
    return true;
  }

  /** Returns one of the CMD_ codes. */
  int touch(float u, float v, boolean isDown)
  {
    float x = u * WIDTH, y = v * HEIGHT;
    int command = CMD_NONE;
    if (isDown && !down)
    {
      activeRow = rowAt(x, y);
      activeSegment = activeRow >= 0 ? segmentAt(rows.get(activeRow), x) : -1;
      if (activeRow >= 0 && rows.get(activeRow).kind == Row.SLIDER)
        setSlider(rows.get(activeRow), x);
      dirty = true;
    }
    else if (isDown)
    {
      if (activeRow >= 0 && rows.get(activeRow).kind == Row.SLIDER)
        setSlider(rows.get(activeRow), x);
    }
    else if (down)
    {
      /* Taps count where they end on what they started on, so a drag off cancels. */
      int row = rowAt(x, y);
      if (row == ROW_CLOSE && activeRow == ROW_CLOSE)
        command = CMD_CLOSE;
      else if (row >= 0 && row == activeRow)
        command = tap(rows.get(row), segmentAt(rows.get(row), x));
      activeRow = -1;
      dirty = true;
    }
    down = isDown;
    return command;
  }

  private int tap(Row row, int segment)
  {
    switch (row.kind)
    {
      case Row.TOGGLE:
        change(row, row.on() ? 0 : 1);
        break;
      case Row.CHOICE:
        if (segment >= 0 && segment == activeSegment)
          change(row, segment);
        break;
      case Row.COMMANDS:
        if (segment >= 0 && segment == activeSegment)
          return row.firstCommand + segment;
        break;
    }
    return CMD_NONE;
  }

  private void setSlider(Row row, float x)
  {
    float f = (x - CONTROL_LEFT) / (sliderRight() - CONTROL_LEFT);
    f = Math.max(0, Math.min(1, f));
    int steps = Math.round(f * (row.max - row.min) / row.step);
    change(row, row.min + steps * row.step);
  }

  private void change(Row row, int value)
  {
    if (value == row.value)
      return;
    row.value = value;
    dirty = true;
    listener.onValueChanged(row);
  }

  private int rowAt(float x, float y)
  {
    if (closeRect().contains(x, y))
      return ROW_CLOSE;
    for (int i = 0; i < rows.size(); i++)
    {
      Row row = rows.get(i);
      if (y >= row.top && y < row.top + row.height)
        return row.enabled ? i : -1;
    }
    return -1;
  }

  /* ---- Layout ---- */

  private static int sliderRight()
  {
    return WIDTH - PAD - 96;
  }

  private RectF closeRect()
  {
    return new RectF(WIDTH - PAD - 64, 24, WIDTH - PAD, 88);
  }

  /* Where a row's segmented buttons go. */
  private void segmentArea(Row row, RectF out)
  {
    if (row.kind == Row.COMMANDS)
    {
      float top = row.twoLines() ? row.top + LINE - 8 : row.top;
      out.set(PAD, top + 4, WIDTH - PAD, row.top + row.height - 4);
    }
    else
      out.set(CONTROL_LEFT, row.top + 6, WIDTH - PAD, row.top + row.height - 6);
  }

  private int segmentAt(Row row, float x)
  {
    if (row.kind != Row.CHOICE && row.kind != Row.COMMANDS)
      return -1;
    RectF area = new RectF();
    segmentArea(row, area);
    if (x < area.left || x >= area.right)
      return -1;
    return (int) ((x - area.left) / (area.width() / row.options.length));
  }

  /* ---- Drawing ---- */

  private void draw()
  {
    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(BACKGROUND);
    rect.set(0, 0, WIDTH, HEIGHT);
    canvas.drawRoundRect(rect, 36, 36, paint);

    paint.setTypeface(Typeface.DEFAULT_BOLD);
    paint.setTextSize(40);
    paint.setColor(TEXT);
    paint.setTextAlign(Paint.Align.LEFT);
    canvas.drawText(title, PAD, 72, paint);

    RectF close = closeRect();
    paint.setColor(activeRow == ROW_CLOSE ? SURFACE_PRESSED : SURFACE);
    canvas.drawOval(close, paint);
    paint.setColor(TEXT);
    paint.setStrokeWidth(5);
    paint.setStrokeCap(Paint.Cap.ROUND);
    float c = 14;
    canvas.drawLine(close.centerX() - c, close.centerY() - c, close.centerX() + c,
        close.centerY() + c, paint);
    canvas.drawLine(close.centerX() - c, close.centerY() + c, close.centerX() + c,
        close.centerY() - c, paint);

    paint.setTypeface(Typeface.DEFAULT);
    for (int i = 0; i < rows.size(); i++)
      drawRow(rows.get(i), i == activeRow);
  }

  private void drawRow(Row row, boolean active)
  {
    int text = row.enabled ? TEXT : TEXT_DISABLED;
    int accent = row.enabled ? ACCENT : TEXT_DISABLED;
    float middle = row.top + LINE / 2f;
    paint.setTextSize(30);
    paint.setTextAlign(Paint.Align.LEFT);
    paint.setColor(text);
    if (row.label.length() > 0)
      canvas.drawText(row.label, PAD, middle + 10, paint);

    switch (row.kind)
    {
      case Row.SLIDER:
      {
        float f = (row.value - row.min) / (float) (row.max - row.min);
        float x = CONTROL_LEFT + f * (sliderRight() - CONTROL_LEFT);
        rect.set(CONTROL_LEFT, middle - 4, sliderRight(), middle + 4);
        paint.setColor(SURFACE_PRESSED);
        canvas.drawRoundRect(rect, 4, 4, paint);
        rect.set(CONTROL_LEFT, middle - 4, x, middle + 4);
        paint.setColor(accent);
        canvas.drawRoundRect(rect, 4, 4, paint);
        canvas.drawCircle(x, middle, active ? 16 : 13, paint);
        paint.setColor(text);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(row.value + row.unit, WIDTH - PAD, middle + 10, paint);
        break;
      }
      case Row.TOGGLE:
      {
        rect.set(WIDTH - PAD - 88, middle - 22, WIDTH - PAD, middle + 22);
        paint.setColor(row.on() ? accent : SURFACE_PRESSED);
        canvas.drawRoundRect(rect, 22, 22, paint);
        paint.setColor(row.on() ? ON_ACCENT : text);
        canvas.drawCircle(row.on() ? rect.right - 22 : rect.left + 22, middle, 16, paint);
        break;
      }
      case Row.CHOICE:
      case Row.COMMANDS:
      {
        RectF area = new RectF();
        segmentArea(row, area);
        float w = area.width() / row.options.length;
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(28);
        for (int s = 0; s < row.options.length; s++)
        {
          boolean selected = row.kind == Row.CHOICE && row.value == s;
          boolean pressed = active && s == activeSegment;
          rect.set(area.left + s * w + 3, area.top, area.left + (s + 1) * w - 3, area.bottom);
          paint.setColor(selected ? accent : pressed ? SURFACE_PRESSED : SURFACE);
          canvas.drawRoundRect(rect, 14, 14, paint);
          paint.setColor(selected ? ON_ACCENT : text);
          canvas.drawText(row.options[s], rect.centerX(), rect.centerY() + 10, paint);
        }
        break;
      }
    }
  }
}
