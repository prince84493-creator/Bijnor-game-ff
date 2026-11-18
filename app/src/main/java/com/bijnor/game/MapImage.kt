package com.bijnor.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pictures of the BIJNOR map for the big map screen and the minimap, painted straight from the map data (land outline + roads):
 *  - the big map uses one [SIZE] x [SIZE] bitmap of the whole map (world x/z in [-half, half] maps to pixel 0..SIZE, z grows downwards),
 *  - the minimap only shows ~190 m around the player, so it is drawn as VECTORS ([drawLocal]) and stays sharp at any zoom.
 * The old island picture is gone completely.
 */
@Suppress("UNUSED_PARAMETER")
class MapImage(ctx0: Context, private val map: MapData) {
    companion object {
        const val SIZE = 2048
        const val SEA = 0xFF1664B2.toInt()
        const val LAND = 0xFF58AD3F.toInt()
        const val COAST = 0xFF2C7A38.toInt()
        const val ROAD = 0xFF14161A.toInt()
        private const val CELL = 128f
        private val lock = Any()
        private var shared: Pair<MapData, Bitmap>? = null
    }
    val half: Float = abs(map.x0)
    private val pic: Bitmap = obtain()
    /** plain sea colour: the full-screen map uses it as its backdrop so the picture edge melts into it */
    val seaColor: Int get() = SEA

    /** The picture of the whole map for a map that is drawn [destW] pixels wide. */
    fun pick(destW: Float): Bitmap = pic

    // ---- road triangles in a 128 m grid (for the vector minimap)
    private var tri = FloatArray(0)            // 6 floats per triangle: ax az bx bz cx cz
    private var gridN = 0
    private var cellStart = IntArray(0)
    private var cellTri = IntArray(0)
    private var stamp = IntArray(0)
    private var stampNow = 0
    private val roadPath = Path(); private val landPath = Path()
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)

    init { buildIndex() }

    private fun buildIndex() {
        var nt = 0
        for (r in map.roadLayers) nt += r.idx.size / 3
        tri = FloatArray(nt * 6)
        var q = 0
        for (r in map.roadLayers) { var i = 0; while (i + 2 < r.idx.size) { for (c in 0 until 3) { tri[q++] = r.xz[r.idx[i + c] * 2]; tri[q++] = r.xz[r.idx[i + c] * 2 + 1] }; i += 3 } }
        gridN = ceil(2f * half / CELL).toInt() + 1
        val counts = IntArray(gridN * gridN + 1)
        fun span(t: Int, f: (Int, Int) -> Unit) {
            val o = t * 6
            val minX = min(tri[o], min(tri[o + 2], tri[o + 4])); val maxX = max(tri[o], max(tri[o + 2], tri[o + 4]))
            val minZ = min(tri[o + 1], min(tri[o + 3], tri[o + 5])); val maxZ = max(tri[o + 1], max(tri[o + 3], tri[o + 5]))
            val i0 = floor((minX + half) / CELL).toInt().coerceIn(0, gridN - 1); val i1 = floor((maxX + half) / CELL).toInt().coerceIn(0, gridN - 1)
            val k0 = floor((minZ + half) / CELL).toInt().coerceIn(0, gridN - 1); val k1 = floor((maxZ + half) / CELL).toInt().coerceIn(0, gridN - 1)
            for (k in k0..k1) for (i in i0..i1) f(i, k)
        }
        for (t in 0 until nt) span(t) { i, k -> counts[k * gridN + i + 1]++ }
        for (c in 0 until gridN * gridN) counts[c + 1] += counts[c]
        cellStart = counts.copyOf()
        cellTri = IntArray(counts[gridN * gridN])
        val fill = IntArray(gridN * gridN)
        for (t in 0 until nt) span(t) { i, k -> val c = k * gridN + i; cellTri[cellStart[c] + fill[c]++] = t }
        stamp = IntArray(nt)
    }

    /**
     * Minimap picture: sea, land, roads (and road connectors) around world point (cx, cz), [viewR] metres in every direction.
     * [r] is the rectangle that the WHOLE map covers on the canvas (same convention as the big map), the canvas is already clipped.
     */
    fun drawLocal(c: Canvas, r: RectF, cx: Float, cz: Float, viewR: Float) {
        val k = r.width() / (2f * half)
        fun sx(x: Float) = r.left + (x + half) * k
        fun sz(z: Float) = r.top + (z + half) * k
        pt.style = Paint.Style.FILL; pt.color = SEA
        c.drawRect(r.left - 1e4f, r.top - 1e4f, r.right + 1e4f, r.bottom + 1e4f, pt)   // sea under everything (the canvas clip limits it)
        // land
        val ring = map.ringXZ
        landPath.reset()
        var i = 0
        while (i + 1 < ring.size) { val x = sx(ring[i]); val y = sz(ring[i + 1]); if (i == 0) landPath.moveTo(x, y) else landPath.lineTo(x, y); i += 2 }
        landPath.close()
        pt.color = LAND; c.drawPath(landPath, pt)
        pt.style = Paint.Style.STROKE; pt.strokeWidth = max(1.2f, 1.6f * k * 2f); pt.strokeJoin = Paint.Join.ROUND; pt.color = COAST; c.drawPath(landPath, pt)
        // roads near the player
        stampNow++
        roadPath.reset()
        val i0 = floor((cx - viewR + half) / CELL).toInt().coerceIn(0, gridN - 1); val i1 = floor((cx + viewR + half) / CELL).toInt().coerceIn(0, gridN - 1)
        val k0 = floor((cz - viewR + half) / CELL).toInt().coerceIn(0, gridN - 1); val k1 = floor((cz + viewR + half) / CELL).toInt().coerceIn(0, gridN - 1)
        for (kk in k0..k1) for (ii in i0..i1) {
            val cell = kk * gridN + ii
            for (q in cellStart[cell] until cellStart[cell + 1]) {
                val t = cellTri[q]
                if (stamp[t] == stampNow) continue
                stamp[t] = stampNow
                val o = t * 6
                roadPath.moveTo(sx(tri[o]), sz(tri[o + 1])); roadPath.lineTo(sx(tri[o + 2]), sz(tri[o + 3])); roadPath.lineTo(sx(tri[o + 4]), sz(tri[o + 5])); roadPath.close()
            }
        }
        pt.style = Paint.Style.FILL_AND_STROKE; pt.strokeWidth = max(1f, 1.2f); pt.strokeJoin = Paint.Join.ROUND; pt.color = ROAD
        c.drawPath(roadPath, pt)
        // connectors
        val cn = map.connXZ
        pt.style = Paint.Style.STROKE; pt.strokeCap = Paint.Cap.ROUND
        var q = 0
        while (q + 4 < cn.size) {
            val x1 = cn[q]; val z1 = cn[q + 1]; val x2 = cn[q + 2]; val z2 = cn[q + 3]; val w = cn[q + 4]; q += 5
            if (abs(x1 - cx) > viewR + 20f && abs(x2 - cx) > viewR + 20f) continue
            if (abs(z1 - cz) > viewR + 20f && abs(z2 - cz) > viewR + 20f) continue
            pt.strokeWidth = max(1.5f, w * k); c.drawLine(sx(x1), sz(z1), sx(x2), sz(z2), pt)
        }
        pt.strokeCap = Paint.Cap.BUTT; pt.style = Paint.Style.FILL
    }

    private fun obtain(): Bitmap {
        synchronized(lock) {
            val old = shared
            if (old != null && old.first === map) return old.second
            val b = makeBig()
            shared = Pair(map, b)
            return b
        }
    }

    /** Whole-map picture for the big map: sea, land with a dark coast line, every road and connector. */
    private fun makeBig(): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = SIZE / (2f * half)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(SEA)
        val ring = map.ringXZ
        val land = Path()
        var i = 0
        while (i + 1 < ring.size) { val x = (ring[i] + half) * s; val y = (ring[i + 1] + half) * s; if (i == 0) land.moveTo(x, y) else land.lineTo(x, y); i += 2 }
        land.close()
        p.style = Paint.Style.FILL; p.color = LAND; c.drawPath(land, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.strokeJoin = Paint.Join.ROUND; p.color = COAST; c.drawPath(land, p)
        p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = 1.2f; p.color = ROAD
        for (r in map.roadLayers) {
            val path = Path()
            var t = 0
            while (t + 2 < r.idx.size) {
                for (cc in 0 until 3) { val x = (r.xz[r.idx[t + cc] * 2] + half) * s; val y = (r.xz[r.idx[t + cc] * 2 + 1] + half) * s; if (cc == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                path.close(); t += 3
            }
            c.drawPath(path, p)
        }
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        val cn = map.connXZ
        var q = 0
        while (q + 4 < cn.size) {
            p.strokeWidth = max(2f, cn[q + 4] * s)
            c.drawLine((cn[q] + half) * s, (cn[q + 1] + half) * s, (cn[q + 2] + half) * s, (cn[q + 3] + half) * s, p)
            q += 5
        }
        return bmp
    }
}
