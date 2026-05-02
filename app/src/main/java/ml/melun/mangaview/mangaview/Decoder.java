package ml.melun.mangaview.mangaview;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;

import static ml.melun.mangaview.Utils.getSample;


public class Decoder {
    int __seed=0;
    int id=0;
    int view_cnt;
    int cx=5, cy=5;
    private volatile int[][] cachedOrder;

    public int getCnt(){
        return view_cnt;
    }

    public Decoder(int seed, int id){
        view_cnt = seed;
        __seed = seed/10;
        this.id = id;
        if(__seed>30000){
            cx = 1;
            cy = 6;
        }else if(__seed>20000){
            cx = 1;
        } else if (__seed>10000) {
            cy = 1;
        }
    }

    public Bitmap decode(Bitmap input, int width){
        input = getSample(input,width);
        return decode(input);
    }
    public Bitmap downSample(final Bitmap input, int maxBytes) {
        if(input.getByteCount() > maxBytes) {
            Float ratio = (maxBytes*1.0f/input.getByteCount());
            return downSize(input, ratio);
        }
        return input;
    }
    public Bitmap downSize(final Bitmap input, Float ratio) {
        int width = Math.max(1, Math.round(input.getWidth() * ratio));
        int height = Math.max(1, Math.round(input.getHeight() * ratio));
        return Bitmap.createScaledBitmap(input, width, height, true);
    }

    public Bitmap decode(Bitmap input){
        input = downSample(input, 100000000);
        if(view_cnt==0) return input;
        int[][] order = getOrder();
        //create new bitmap
        Bitmap output = Bitmap.createBitmap(input.getWidth(), input.getHeight(), Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(output);

        int imageWidth = input.getWidth();
        int imageHeight = input.getHeight();
        Rect src = new Rect();
        Rect dst = new Rect();
        for (int i = 0; i < cx*cy; i++) {
            int[] o = order[i];
            int ox = i % cx;
            int oy = i / cx;
            int tx = o[0] % cx;
            int ty = o[0] / cx;
            setCellRect(src, imageWidth, imageHeight, ox, oy);
            setCellRect(dst, imageWidth, imageHeight, tx, ty);
            if(!src.isEmpty() && !dst.isEmpty())
                canvas.drawBitmap(input, src, dst, null);
        }
        return output;
    }

    private int[][] getOrder() {
        if(cachedOrder != null)
            return cachedOrder;
        int[][] order = new int[cx*cy][2];
        for (int i = 0; i < cx*cy; i++) {
            order[i][0] = i;
            if (id < 554714) order[i][1] = _random(i);
            else order[i][1] = newRandom(i);
        }
        java.util.Arrays.sort(order, (a, b) -> {
            return a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[0], b[0]);
        });
        cachedOrder = order;
        return cachedOrder;
    }

    private void setCellRect(Rect rect, int width, int height, int x, int y) {
        rect.set(
                width * x / cx,
                height * y / cy,
                width * (x + 1) / cx,
                height * (y + 1) / cy
        );
    }

    private int _random(int index){
        double x = Math.sin(__seed+index) * 10000;
        return (int) Math.floor((x - Math.floor(x)) * 100000);
    }

    private int newRandom(int index){
        index++;
        double t = 100 * Math.sin(10 * (__seed+index))
                , n = 1000 * Math.cos(13 * (__seed+index))
                , a = 10000 * Math.tan(14 * (__seed+index));
        t = Math.floor(100 * (t - Math.floor(t)));
        n = Math.floor(1000 * (n - Math.floor(n)));
        a = Math.floor(10000 * (a - Math.floor(a)));
        return (int)(t + n + a);
    }
}
