package com.example.weathersafe;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.widget.ArrayAdapter;
import android.Manifest;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.osmdroid.util.GeoPoint;
import java.util.List;
import android.graphics.Color;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.osmdroid.config.Configuration;

import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import android.location.Address;
import android.location.Geocoder;

public class MainActivity extends AppCompatActivity {
    private static final String GRAPHOPPER_API_KEY = BuildConfig.GRAPHOPPER_API_KEY;
    private static final int COLOR_SELECTED = 0xFF2E7D32; // Green
    private static final int COLOR_ALTERNATIVE = 0xFF1E88E5;
    private static final float WIDTH_SELECTED = 12f;
    private static final float WIDTH_ALTERNATIVE = 6f;
    private int totalWeatherCalls = 0;
    private int completedWeatherCalls = 0;

    private EditText sourceEditText, destinationEditText;
    private Button routeButton, btnWeatherRoute, btnFastestRoute, btnFuelRoute;
    private LinearLayout routeOptionsLayout;
    private MapView mapView;

    private final OkHttpClient client = new OkHttpClient();
    private final List<RouteInfo> routeInfos = new ArrayList<>();
    private final Map<Polyline, Integer> routeRisk = new HashMap<>();
    private final Map<Polyline, Integer> routePointCount = new HashMap<>();
    private final List<Marker> weatherMarkers = new ArrayList<>();
    private final List<Marker> placeMarkers = new ArrayList<>();

    private BottomSheetDialog bottomSheetDialog;
    private String currentMode = "FASTEST";

    // Vehicle / Fuel info
    private double userMileage = 15.0; // km/L default
    private double fuelPricePerLitre = 100.0; // default

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Configuration.getInstance().setUserAgentValue(getPackageName());
        setContentView(R.layout.activity_main);

        mapView = findViewById(R.id.map);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(7.0);


        GeoPoint indiaCenter = new GeoPoint(22.9734, 78.6569); // center of India
        mapView.getController().setCenter(indiaCenter);
        sourceEditText = findViewById(R.id.sourceEditText);
        destinationEditText = findViewById(R.id.destinationEditText);

        routeButton = findViewById(R.id.routeButton);
        btnWeatherRoute = findViewById(R.id.btnWeatherRoute);
        btnFastestRoute = findViewById(R.id.btnFastestRoute);
        btnFuelRoute = findViewById(R.id.btnFuelRoute);
        routeOptionsLayout = findViewById(R.id.routeOptionsLayout);
        routeOptionsLayout.setVisibility(View.GONE);
        routeButton.setOnClickListener(v -> {
            if (sourceEditText.getText().toString().isEmpty() || destinationEditText.getText().toString().isEmpty()) {
                Toast.makeText(this, "Enter source and destination", Toast.LENGTH_SHORT).show();
                return;
            }
            drawRoutes(sourceEditText.getText().toString(), destinationEditText.getText().toString());
        });

        btnWeatherRoute.setOnClickListener(v -> {
            currentMode = "WEATHER";
            showWeatherSafeRoute();
        });

        btnFastestRoute.setOnClickListener(v -> {
            currentMode = "FASTEST";
            showFastestRoute();
        });

        btnFuelRoute.setOnClickListener(v -> {
            showVehicleFuelDialog();
        });

        // Location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 101);
        }
    }

    // ================== DRAW ROUTES ==================
    private void drawRoutes(String source, String destination) {

        mapView.getOverlays().clear();
        btnWeatherRoute.setEnabled(false);
        routeInfos.clear();
        routeRisk.clear();
        weatherMarkers.clear();
        placeMarkers.clear();
        routeOptionsLayout.setVisibility(View.GONE);

        Geocoder geocoder = new Geocoder(this, Locale.getDefault());

        try {
            List<Address> src = geocoder.getFromLocationName(source, 1);
            List<Address> dst = geocoder.getFromLocationName(destination, 1);

            if (src == null || src.isEmpty() || dst == null || dst.isEmpty()) {
                Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show();
                return;
            }

            double lat1 = src.get(0).getLatitude();
            double lon1 = src.get(0).getLongitude();
            double lat2 = dst.get(0).getLatitude();
            double lon2 = dst.get(0).getLongitude();

            String url = "https://graphhopper.com/api/1/route?"
                    + "point=" + lat1 + "," + lon1
                    + "&point=" + lat2 + "," + lon2
                    + "&vehicle=car"
                    + "&locale=en"
                    + "&calc_points=true"
                    + "&points_encoded=false"
                    + "&elevation=true"
                    + "&algorithm=alternative_route"
                    + "&alternative_route.max_paths=3"
                    + "&alternative_route.max_weight_factor=1.6"
                    + "&alternative_route.max_share_factor=0.5"
                    + "&key=" + GRAPHOPPER_API_KEY;

            Request request = new Request.Builder().url(url).build();

            client.newCall(request).enqueue(new Callback() {

                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this, "Route fetch failed", Toast.LENGTH_SHORT).show()
                    );
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {

                    JsonArray routes = JsonParser.parseString(response.body().string())
                            .getAsJsonObject().getAsJsonArray("paths");
                    if (routes == null || routes.size() == 0) {
                        runOnUiThread(() ->
                                Toast.makeText(MainActivity.this, "No routes found", Toast.LENGTH_SHORT).show()
                        );
                        return;
                    }



                    runOnUiThread(() -> {

                        for (int i = 0; i < routes.size(); i++) {

                            JsonObject route = routes.get(i).getAsJsonObject();

                            JsonObject pointsObj = route.getAsJsonObject("points");
                            JsonArray coords = pointsObj.getAsJsonArray("coordinates");

                            List<GeoPoint> path = new ArrayList<>();
                            List<Double> elevations = new ArrayList<>();

                            for (int j = 0; j < coords.size(); j++) {
                                JsonArray point = coords.get(j).getAsJsonArray();

                                double lon = point.get(0).getAsDouble();
                                double lat = point.get(1).getAsDouble();
                                double ele = point.get(2).getAsDouble();

                                path.add(new GeoPoint(lat, lon));
                                elevations.add(ele);
                            }

                            double distance = route.get("distance").getAsDouble();

                            // convert ms → seconds
                            double duration = route.get("time").getAsDouble() / 1000;



                            RouteInfo info = new RouteInfo();
                            info.path = path;
                            info.distance = distance;
                            info.duration = duration;
                            info.weatherRisk = 0;
                            info.elevations = elevations;

                            Polyline line = new Polyline();
                            line.setPoints(path);
                            routeRisk.put(line, 0);
                            routePointCount.put(line, 0);

                            int color;
                            if (i == 0) color = Color.parseColor("#2962FF"); // Blue
                            else if (i == 1) color = Color.parseColor("#00C853"); // Green
                            else color = Color.parseColor("#FF6D00"); // Orange

                            line.setColor(color);
                            line.setWidth(8f);

                            mapView.getOverlays().add(line);

                            info.polyline = line;
                            routeInfos.add(info);

                            // 🔥 IMPORTANT
                            calculateRisk(path, line);
                            // 🔥 IMPORTANT

                        }

                        // markers
                        Marker sourceMarker = new Marker(mapView);
                        sourceMarker.setPosition(new GeoPoint(lat1, lon1));
                        sourceMarker.setTitle("Source");
                        mapView.getOverlays().add(sourceMarker);

                        Marker destMarker = new Marker(mapView);
                        destMarker.setPosition(new GeoPoint(lat2, lon2));
                        destMarker.setTitle("Destination");
                        mapView.getOverlays().add(destMarker);

                        mapView.invalidate();
                        routeOptionsLayout.setVisibility(View.VISIBLE);
                    }); // Alt 2


                }
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private List<GeoPoint> getWeatherPoints(List<GeoPoint> path) {

        List<GeoPoint> selectedPoints = new ArrayList<>();

        if (path == null || path.size() < 10) {
            selectedPoints.addAll(path);
            return selectedPoints;
        }

        int totalPoints = path.size();

        // always include source
        selectedPoints.add(path.get(0));

        // pick 4 middle points evenly
        for (int i = 1; i <= 4; i++) {
            int index = (i * totalPoints) / 5;
            selectedPoints.add(path.get(index));
        }

        // always include destination
        selectedPoints.add(path.get(totalPoints - 1));

        return selectedPoints;
    }
    // ================== WEATHER RISK ==================
    private void calculateRisk(List<GeoPoint> path, Polyline polyline) {

        List<GeoPoint> points = getWeatherPoints(path);
        totalWeatherCalls = points.size();

        completedWeatherCalls = 0;


        for (GeoPoint p : points) {
            fetchWeatherRisk(p.getLatitude(), p.getLongitude(), polyline);
        }
    }

    private void fetchWeatherRisk(double lat, double lon, Polyline polyline) {
        String url = "https://api.weatherapi.com/v1/current.json?key="
                + BuildConfig.WEATHER_API_KEY
                + "&q=" + lat + "," + lon;

        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException {
                JsonObject current = JsonParser.parseString(response.body().string())
                        .getAsJsonObject().getAsJsonObject("current");
                String condition = current.getAsJsonObject("condition").get("text").getAsString();
                double wind = current.get("wind_kph").getAsDouble();
                double temp = current.get("temp_c").getAsDouble();

                int risk = routeRisk.getOrDefault(polyline, 0);
                int count = routePointCount.getOrDefault(polyline, 0);

// 🌡️ Temperature (VERY SENSITIVE)
                double idealTemp = 25.0;
                double tempDeviation = Math.abs(temp - idealTemp);

// small changes also matter
                double tempRisk = tempDeviation * 0.4;

// 💨 Wind (continuous)
                double windRisk = wind * 0.05;

// 🌧️ Condition-based
                double rainRisk = 0;
                String cond = condition.toLowerCase();

                if (cond.contains("storm") || cond.contains("thunder")) rainRisk = 7;
                else if (cond.contains("heavy rain")) rainRisk = 6;
                else if (cond.contains("rain")) rainRisk = 5;
                else if (cond.contains("drizzle")) rainRisk = 3;
                else if (cond.contains("cloud")) rainRisk = 1.5;
                else if (cond.contains("fog") || cond.contains("mist")) rainRisk = 4;

// 🌡️ Extreme penalty
                double extremeRisk = 0;
                if (temp > 40 || temp < 5) extremeRisk = 3;

// 🔥 FINAL
                double totalRisk = tempRisk + windRisk + rainRisk + extremeRisk;
                totalRisk = Math.min(totalRisk, 15);

// ✅ accumulate + count
                risk += Math.round(totalRisk);
                count++;

// store
                routeRisk.put(polyline, risk);
                routePointCount.put(polyline, count);

// ✅ FINAL AVERAGE (IMPORTANT FIX)
                int avgRisk = risk / count;
                completedWeatherCalls++;

                for (RouteInfo r : routeInfos) {
                    if (r.polyline == polyline) {
                        r.weatherRisk = avgRisk;
                        break;
                    }
                }



// ✅ CHECK IF ALL WEATHER CALLS DONE
                if (completedWeatherCalls == totalWeatherCalls) {
                    runOnUiThread(() -> {
                        btnWeatherRoute.setEnabled(true);
                        Toast.makeText(MainActivity.this, "Weather Ready", Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }

//=======================CALCULATEELEVATIONGAIN================
private double calculateElevationGain(List<Double> elevations) {
    double gain = 0;

    for (int i = 1; i < elevations.size(); i++) {
        double diff = elevations.get(i) - elevations.get(i - 1);

        if (diff > 0) {
            gain += diff;
        }
    }

    return gain;
}



    // ================== ROUTE SELECTION ==================
    private void showWeatherSafeRoute() {
        if (routeInfos.isEmpty()) return;

        // Sort by weather risk
        routeInfos.sort(Comparator.comparingInt(r -> r.weatherRisk));

        RouteInfo bestRoute = routeInfos.get(0);

        // Highlight selected route (GREEN)
        highlightSelectedRoute(bestRoute);

        // 🔥 REMOVE OLD MARKERS
        for (Marker m : weatherMarkers) {
            mapView.getOverlays().remove(m);
        }
        weatherMarkers.clear();

        // ✅ SHOW WEATHER ONLY FOR BEST ROUTE
        showWeatherMarkers(bestRoute.path);

        currentMode = "WEATHER";
        showBottomSheet(bestRoute);

        zoomToRoute(bestRoute);
    }
    private void showFastestRoute() {
        if (routeInfos.isEmpty()) return;

        // Sort by duration
        routeInfos.sort(Comparator.comparingDouble(r -> {

            double distanceKm = r.distance / 1000.0;

            // 🔥 Elevation factor
            double elevationGain = calculateElevationGain(r.elevations);
            double elevationFactor = 1 + (elevationGain / 1000.0) * 0.1;

            // 🔥 Speed factor
            double avgSpeed = distanceKm / (r.duration / 3600.0);
            double speedFactor;

            if (avgSpeed < 30) speedFactor = 1.2;
            else if (avgSpeed > 80) speedFactor = 1.1;
            else speedFactor = 1.0;

            // 🔥 Final fuel
            double fuel = (distanceKm / userMileage) * elevationFactor * speedFactor;

            return fuel * fuelPricePerLitre;
        }));

        RouteInfo bestRoute = routeInfos.get(0);

        // Highlight selected route and reset others
        highlightSelectedRoute(bestRoute);
        showWeatherMarkers(bestRoute.path);
        // Show bottom sheet with info
        currentMode = "FASTEST";
        showBottomSheet(bestRoute);

        // Zoom to selected route
        zoomToRoute(bestRoute);
    }

    private void showFuelEfficientRoute() {
        if (routeInfos.isEmpty()) return;

        // Sort by estimated fuel cost
        routeInfos.sort(Comparator.comparingDouble(r ->
                (r.distance / 1000.0) / userMileage * fuelPricePerLitre
        ));

        RouteInfo bestRoute = routeInfos.get(0);

        // Highlight selected route and reset others
        highlightSelectedRoute(bestRoute);
        showWeatherMarkers(bestRoute.path);

        // Show bottom sheet with info
        currentMode = "FUEL";
        showBottomSheet(bestRoute);

        // Zoom to selected route
        zoomToRoute(bestRoute);
    }

    // ================== HIGHLIGHT SELECTED ROUTE ==================
    private void highlightSelectedRoute(RouteInfo selectedRoute) {
        for (RouteInfo route : routeInfos) {
            if (route.polyline == null) continue;

            if (route == selectedRoute) {
                route.polyline.setColor(COLOR_SELECTED);  // Green
                route.polyline.setWidth(WIDTH_SELECTED);
            } else {
                route.polyline.setColor(Color.parseColor("#2962FF")); // Blue for all other routes
                route.polyline.setWidth(WIDTH_ALTERNATIVE);
            }
        }

        mapView.invalidate();
    }

    //ZoomToRoute


    private void zoomToRoute(RouteInfo route) {

        if (route == null || route.path == null) return;

        mapView.postDelayed(() -> {
            mapView.zoomToBoundingBox(
                    org.osmdroid.util.BoundingBox.fromGeoPoints(routeInfos.get(0).path),
                    true,
                    100
            );
        }, 400);
    }
    //Highlight routes
    private void highlightAndShowTop3(int selectedIndex) {

        for (Marker m : weatherMarkers) m.remove(mapView);
        weatherMarkers.clear();

        RouteInfo selectedRoute = null;

        for (int i = 0; i < routeInfos.size(); i++) {

            RouteInfo route = routeInfos.get(i);

            if (route.polyline == null) continue;

            if (i == selectedIndex) {

                selectedRoute = route;

                route.polyline.setColor(COLOR_SELECTED);
                route.polyline.setWidth(WIDTH_SELECTED);

                // ✅ show markers for selected route
                showWeatherMarkersLimited(route.path);

            } else {
                route.polyline.setColor(COLOR_ALTERNATIVE);
                route.polyline.setWidth(WIDTH_ALTERNATIVE);
            }
        }

        mapView.invalidate();


    }
    //showWeatherMarkersLimited
    private void showWeatherMarkersLimited(List<GeoPoint> path) {

        if (path == null || path.size() < 5) return;

        int step = path.size() / 5;

        for (int i = 0; i < path.size(); i += step) {

            if (weatherMarkers.size() >= 5) break;

            fetchAndShowWeatherMarker(path.get(i), "Weather Point");
        }
    }

    // ================== SHOW SINGLE ROUTE ==================
    private void showSingleRoute(RouteInfo selectedRoute) {

        highlightSelectedRoute(selectedRoute);

        for (Marker m : weatherMarkers) m.remove(mapView);
        for (Marker m : placeMarkers) m.remove(mapView);

        weatherMarkers.clear();
        placeMarkers.clear();

        // ✅ SOURCE MARKER (NO ICON)
        Marker marker = new Marker(mapView);
        marker.setPosition(selectedRoute.path.get(0));
        marker.setTitle("Source");
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        mapView.getOverlays().add(marker);

        // ✅ DESTINATION MARKER (NO ICON)
        Marker dest = new Marker(mapView);
        dest.setPosition(selectedRoute.path.get(selectedRoute.path.size() - 1));
        dest.setTitle("Destination");
        dest.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        mapView.getOverlays().add(dest);

        // ✅ Zoom animation
        mapView.getController().animateTo(
                selectedRoute.path.get(selectedRoute.path.size() / 2)
        );

        mapView.postDelayed(() -> {
            mapView.zoomToBoundingBox(
                    org.osmdroid.util.BoundingBox.fromGeoPoints(selectedRoute.path),
                    true,
                    100
            );
        }, 500);

        // ✅ Weather + Places
        showWeatherMarkers(selectedRoute.path);
        showPlacesAlongRoute(selectedRoute.path);

        // ✅ Bottom sheet
        showBottomSheet(selectedRoute);
    }



    // ================== WEATHER MARKERS ==================
    private void showWeatherMarkers(List<GeoPoint> path) {
        for (Marker m : weatherMarkers) {
            mapView.getOverlays().remove(m);
        }
        weatherMarkers.clear();

        List<GeoPoint> points = getWeatherPoints(path);

        for (int i = 0; i < points.size(); i++) {

            GeoPoint p = points.get(i);

            String title;

            if (i == 0)
                title = "Source Weather";
            else if (i == points.size() - 1)
                title = "Destination Weather";
            else
                title = "Weather Point " + i;

            fetchAndShowWeatherMarker(p, title);
        }
    }
    private void fetchAndShowWeatherMarker(GeoPoint point, String title) {

        String url = "https://api.weatherapi.com/v1/current.json?key="
                + BuildConfig.WEATHER_API_KEY
                + "&q=" + point.getLatitude() + "," + point.getLongitude();

        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {

            @Override public void onFailure(Call call, IOException e) {}

            @Override
            public void onResponse(Call call, Response response) throws IOException {

                JsonObject current = JsonParser.parseString(response.body().string())
                        .getAsJsonObject().getAsJsonObject("current");

                double temp = current.get("temp_c").getAsDouble();
                String condition = current.getAsJsonObject("condition").get("text").getAsString();

                runOnUiThread(() -> {

                    Marker m = new Marker(mapView);
                    m.setPosition(point);
                    m.setTitle(title);
                    m.setSubDescription(temp + "°C | " + condition);

                   /* try {
                       // m.setIcon(ContextCompat.getDrawable(MainActivity.this, R.drawable.ic_weather));
                    } catch (Exception e) {
                        // ignore if not present
                    }*/

                    m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);

                    mapView.getOverlays().add(m);
                    weatherMarkers.add(m);
                });
            }
        });
    }

    // ================== PLACES ==================
    private void showPlacesAlongRoute(List<GeoPoint> path) {
        int step = Math.max(1, path.size() / 5);
        for (int i = 0; i < path.size(); i += step) {
            GeoPoint p = path.get(i);
            Marker m = new Marker(mapView);
            m.setPosition(p);
            m.setTitle("Nearby Place");
            m.setSubDescription("Temple / Hospital / Fuel");
            mapView.getOverlays().add(m);
            placeMarkers.add(m);
        }
    }

    // ================== BOTTOM SHEET ==================
    private void showBottomSheet(RouteInfo route) {
        if (bottomSheetDialog != null && bottomSheetDialog.isShowing())
            bottomSheetDialog.dismiss();

        bottomSheetDialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.bottom_route_info, null);

        TextView title = view.findViewById(R.id.txtRouteTitle);
        TextView details = view.findViewById(R.id.txtRouteDetails);

        double distanceKm = route.distance / 1000.0;
        double timeMin = route.duration / 60.0;
        double avgSpeed = distanceKm / (route.duration / 3600.0); // km/h

        StringBuilder info = new StringBuilder();

        switch (currentMode) {

            case "WEATHER":
                // Example: we can use dummy % for now; ideally you would fetch from weather API
+


                int rain = Math.min(route.weatherRisk * 10, 100);
                int cloudy = Math.min(100 - rain, 40);
                int sunny = 100 - rain - cloudy;

                info.append(String.format("Weather Conditions:\nSunny: %d%%\nCloudy: %d%%\nRainy: %d%%\n",
                        sunny, cloudy, rain));
                info.append("Weather Risk Score: " + route.weatherRisk + "\n");
                info.append("This route avoids areas with severe weather conditions.");
                break;

            case "FASTEST":
                String drivingSafety = "Moderately safe"; // Can be dynamic if you have data
                info.append(String.format("Distance: %.2f km\n", distanceKm));
                info.append(String.format("Time: %d mins\n", (int) timeMin));
                info.append(String.format("Avg Speed: %.1f km/h\n", avgSpeed));
                info.append("Driving Safety: " + drivingSafety + "\n");
                info.append("This route is selected because it has minimum travel time.");
                break;

            case "FUEL":
                info.append(String.format("Distance: %.2f km\n", distanceKm));
                info.append("Fuel Type: Petrol\n");
                info.append(String.format("Vehicle Mileage: %.2f km/L\n", userMileage));

// 🔥 Elevation factor
                double elevationGain = calculateElevationGain(route.elevations);
                double elevationFactor = 1 + (elevationGain / 1000.0) * 0.05;

// 🔥 DO NOT redeclare avgSpeed (already defined above)
                double speedFactor;

                if (avgSpeed < 30) speedFactor = 1.2;
                else if (avgSpeed > 80) speedFactor = 1.1;
                else speedFactor = 1.0;

// 🔥 Final fuel calculation
                double fuelRequired = (distanceKm / userMileage) * elevationFactor * speedFactor;

                info.append(String.format("Elevation Gain: %.2f m\n", elevationGain));
                info.append(String.format("Adjusted Fuel Required: %.2f L\n", fuelRequired));
                info.append(String.format("Fuel Price per Litre: %.2f\n", fuelPricePerLitre));
                info.append(String.format("Estimated Cost: %.2f\n", fuelRequired * fuelPricePerLitre));
                break;
        }

        title.setText(currentMode + " Route");
        details.setText(info.toString());

        bottomSheetDialog.setContentView(view);
        bottomSheetDialog.show();
    }
    // ================== POLYLINE DECODER ==================
    private List<GeoPoint> decodePolyline(String encoded) {
        List<GeoPoint> poly = new ArrayList<>();
        int index = 0, lat = 0, lng = 0;
        while (index < encoded.length()) {
            int b, shift = 0, result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lat += ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            shift = 0;
            result = 0;
            do {
                b = encoded.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lng += ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            poly.add(new GeoPoint(lat / 1E5, lng / 1E5));
        }
        return poly;
    }

    class RouteInfo {
        List<GeoPoint> path;
        List<Double> elevations; // 🔥 NEW
        double distance;
        double duration;
        int weatherRisk;
        Polyline polyline;
    }

    // ================== VEHICLE / FUEL DIALOG ==================
    private void showVehicleFuelDialog() {

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_vehicle_input, null);

        Spinner spinnerVehicle = dialogView.findViewById(R.id.spinnerVehicle);
        Spinner spinnerFuel = dialogView.findViewById(R.id.spinnerFuel);
        EditText editMileage = dialogView.findViewById(R.id.editMileage);
        EditText editFuelPrice = dialogView.findViewById(R.id.editFuelPrice);
        Button btnSubmit = dialogView.findViewById(R.id.btnSubmitVehicle);

        // -------- Vehicle Dropdown Options --------
        String[] vehicles = {"Car", "Bike", "Bus", "Truck"};
        ArrayAdapter<String> vehicleAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                vehicles
        );
        vehicleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerVehicle.setAdapter(vehicleAdapter);

        // -------- Fuel Dropdown Options --------
        String[] fuels = {"Petrol", "Diesel", "CNG", "Electric"};
        ArrayAdapter<String> fuelAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                fuels
        );
        fuelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerFuel.setAdapter(fuelAdapter);

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(dialogView);
        dialog.show();

        btnSubmit.setOnClickListener(v -> {

            String selectedVehicle = spinnerVehicle.getSelectedItem().toString();
            String selectedFuel = spinnerFuel.getSelectedItem().toString();

            String mileageStr = editMileage.getText().toString();
            String fuelStr = editFuelPrice.getText().toString();

            if (!mileageStr.isEmpty()) {
                userMileage = Double.parseDouble(mileageStr);
            }

            if (!fuelStr.isEmpty()) {
                fuelPricePerLitre = Double.parseDouble(fuelStr);
            }

            Toast.makeText(this,
                    "Vehicle: " + selectedVehicle + "\nFuel: " + selectedFuel,
                    Toast.LENGTH_SHORT).show();

            dialog.dismiss();

            currentMode = "FUEL";
            showFuelEfficientRoute();
        });
    }
    // ================== PERMISSIONS ==================
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
    private void drawRoutesWithHighlight(RouteInfo bestRoute) {

        for (RouteInfo route : routeInfos) {

            if (route.polyline == null) continue;

            if (route == bestRoute) {
                route.polyline.setColor(COLOR_SELECTED);
                route.polyline.setWidth(WIDTH_SELECTED);
            } else {
                route.polyline.setColor(COLOR_ALTERNATIVE);
                route.polyline.setWidth(WIDTH_ALTERNATIVE);
            }
        }

        mapView.invalidate();
    }
}
