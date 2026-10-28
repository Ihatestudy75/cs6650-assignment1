import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import model.LiftRide;
import model.ResponseMsg;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.logging.Logger;
import java.util.logging.Level;
import util.RequestStats;

public class SkierServlet extends HttpServlet {
  private static final Logger logger = Logger.getLogger(SkierServlet.class.getName());
  private Gson gson = new Gson();

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res)
      throws ServletException, IOException {

    logger.info("GET request received: " + req.getPathInfo());

    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");

    String urlPath = req.getPathInfo();

    // Special endpoint for statistics
    if ("/stats".equals(urlPath)) {
      res.setContentType("application/json");
      res.setStatus(HttpServletResponse.SC_OK);
      res.getWriter().write(gson.toJson(new ResponseMsg(RequestStats.getStats())));
      return;
    }

    RequestStats.incrementGet();

    // Check we have a URL
    if (urlPath == null || urlPath.isEmpty()) {
      res.setStatus(HttpServletResponse.SC_NOT_FOUND);
      res.getWriter().write(gson.toJson(new ResponseMsg("missing parameters")));
      return;
    }

    String[] urlParts = urlPath.split("/");

    if (!isUrlValid(urlParts)) {
      logger.warning("Invalid URL: " + urlPath);
      res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      res.getWriter().write(gson.toJson(new ResponseMsg("invalid url")));
    } else {
      res.setStatus(HttpServletResponse.SC_OK);

      // Extract parameters
      String resortID = urlParts[1];
      String seasonID = urlParts[3];
      String dayID = urlParts[5];
      String skierID = urlParts[7];

      // Create response
      ResponseMsg response = new ResponseMsg(
          String.format("GET request processed for skier %s at resort %s, season %s, day %s",
              skierID, resortID, seasonID, dayID)
      );

      res.getWriter().write(gson.toJson(response));

      logger.info("Valid GET request processed for: " + urlPath);
    }
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res)
      throws ServletException, IOException {

    logger.info("POST request received: " + req.getPathInfo());

    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");

    String urlPath = req.getPathInfo();

    // Missing swipe parameters are an invalid request: return HTTP 400.
    if (urlPath == null || urlPath.isEmpty()) {
      res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      res.getWriter().write(gson.toJson(new ResponseMsg("missing parameters")));
      return;
    }

    String[] urlParts = urlPath.split("/");

    if (!isUrlValid(urlParts)) {
      logger.warning("Invalid URL: " + urlPath);
      res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      res.getWriter().write(gson.toJson(new ResponseMsg("invalid url")));
      return;
    } else {
      logger.info("Valid POST request processed for: " + req.getPathInfo());
    }

    // Parse JSON body
    try {
      StringBuilder jsonBody = new StringBuilder();
      BufferedReader reader = req.getReader();
      String line;
      while ((line = reader.readLine()) != null) {
        jsonBody.append(line);
      }

      LiftRide liftRide = gson.fromJson(jsonBody.toString(), LiftRide.class);

      if (liftRide == null || !isLiftRideValid(liftRide)) {
        res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        res.getWriter().write(gson.toJson(new ResponseMsg("invalid JSON body")));
        return;
      }

      // Extract URL parameters
      String resortID = urlParts[1];
      String seasonID = urlParts[3];
      String dayID = urlParts[5];
      String skierID = urlParts[7];

      // Process the lift ride (in real app, save to database)
      res.setStatus(HttpServletResponse.SC_CREATED);
      ResponseMsg response = new ResponseMsg(
          String.format("Lift ride recorded: skier %s, resort %s, season %s, day %s, time %d, lift %d",
              skierID, resortID, seasonID, dayID, liftRide.getTime(), liftRide.getLiftID())
      );

      res.getWriter().write(gson.toJson(response));

      logger.info("Valid POST request processed for: " + urlPath);

    } catch (JsonSyntaxException e) {
      res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      res.getWriter().write(gson.toJson(new ResponseMsg("invalid JSON format")));
    }
  }

  private boolean isUrlValid(String[] urlParts) {
    // Expected URL: /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}
    // urlParts = ["", "1", "seasons", "2019", "days", "1", "skiers", "123"]

    if (urlParts.length != 8) {
      return false;
    }

    try {
      // Check structure
      if (!urlParts[2].equals("seasons") ||
          !urlParts[4].equals("days") ||
          !urlParts[6].equals("skiers")) {
        return false;
      }

      // Parse each ID, then require a positive value; parseInt also accepts zero and negatives.
      int resortID = Integer.parseInt(urlParts[1]); // resortID
      if (resortID <= 0) return false;

      int seasonID = Integer.parseInt(urlParts[3]); // seasonID
      if (seasonID <= 0) return false;

      int dayID = Integer.parseInt(urlParts[5]); // dayID
      if (dayID <= 0) return false;

      int skierID = Integer.parseInt(urlParts[7]); // skierID
      if (skierID <= 0) return false;

      return true;

    } catch (NumberFormatException e) {
      // Reject non-integer text and values outside Java's int range.
      return false;
    }
  }

  private boolean isLiftRideValid(LiftRide liftRide) {
    // Assignment 1 allows minutes 1–360 and lift IDs 1–40, including both boundaries.
    return liftRide.getTime() > 0 &&
        liftRide.getTime() <= 360 && // Max 6 hours (360 minutes)
        liftRide.getLiftID() > 0 &&
        liftRide.getLiftID() <= 40;
  }
}
