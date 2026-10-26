package com.sullivan.android.ui.guardian.call;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.amap.api.services.core.LatLonPoint;
import com.amap.api.services.geocoder.GeocodeSearch;
import com.amap.api.services.geocoder.RegeocodeQuery;
import com.amap.api.services.geocoder.RegeocodeResult;

/**
 * 把坐标解析成地名（高德逆地理编码）。
 *
 * <p><b>为什么用搜索 SDK 而不是 REST API</b>：REST（restapi.amap.com）要求
 * 「Web 服务」类型的 Key，与工程里已有的 Android Key 不是同一个；
 * 而搜索 SDK 的 {@link GeocodeSearch} 就用 manifest 里那个 Android Key
 * （定位 SDK 也在用它），不再多管一个密钥。
 *
 * <p><b>失败是常态而非异常</b>：无网、Key 配额、服务区外，都拿不到地名。
 * 所有失败统一回调 {@code null}，调用方回落到原有的坐标展示 ——
 * 坐标永远比没有信息强。
 */
public final class PlaceNameResolver {

    /** 解析完成回调。**在主线程回调**；拿不到地名时 {@code name} 为 {@code null}。 */
    public interface Callback {
        void onPlaceName(@Nullable String name);
    }

    private PlaceNameResolver() {
    }

    /**
     * 把 WGS84 坐标解析成一句人话地名（省市区 + 道路/地标）。
     *
     * @param latitude  纬度
     * @param longitude 经度
     * @param callback  结果回调；解析失败时回调 {@code null}
     */
    public static void resolve(@NonNull Context context, double latitude, double longitude,
                               @NonNull Callback callback) {
        GeocodeSearch search;
        try {
            search = new GeocodeSearch(context);
        } catch (com.amap.api.services.core.AMapException error) {
            // SDK 初始化失败（Key 缺失等）按"没有地名"处理，不崩。
            callback.onPlaceName(null);
            return;
        }
        search.setOnGeocodeSearchListener(new GeocodeSearch.OnGeocodeSearchListener() {
            @Override
            public void onRegeocodeSearched(@Nullable RegeocodeResult result, int code) {
                // code == 1000 是高德的「成功」。
                String name = null;
                if (code == 1000 && result != null
                        && result.getRegeocodeAddress() != null) {
                    name = trimCountry(result.getRegeocodeAddress().getFormatAddress());
                }
                callback.onPlaceName(name);
            }

            @Override
            public void onGeocodeSearched(@Nullable com.amap.api.services.geocoder.GeocodeResult
                                                  result, int code) {
                // 本类只做逆地理（坐标→地名），正向编码不会走到。
            }
        });
        // 搜索半径 50 米：报警定位精度通常在几十米内，取最近的地址即可。
        RegeocodeQuery query = new RegeocodeQuery(
                new LatLonPoint(latitude, longitude), 50, GeocodeSearch.AMAP);
        try {
            search.getFromLocationAsyn(query);
        } catch (RuntimeException error) {
            callback.onPlaceName(null);
        }
    }

    /**
     * 高德返回的完整地址以「中国」开头，给监护人看的场景里是废话 ——
     * 都在国内，砍掉省一层格式化前的冗余。
     */
    @Nullable
    private static String trimCountry(@Nullable String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }
        String trimmed = address.startsWith("中国") ? address.substring("中国".length()) : address;
        return trimmed.trim().isEmpty() ? null : trimmed;
    }
}
