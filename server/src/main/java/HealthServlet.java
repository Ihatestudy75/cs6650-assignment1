import java.io.IOException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet("/health")
public class HealthServlet extends HttpServlet {
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res)
    throws IOException {
    // Set the response type and encoding
    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");

    // Set HTTP status 200
    res.setStatus(HttpServletResponse.SC_OK);

    // Write a fixed JSON response
    res.getWriter().write("{\"status\":\"UP\"}");
  }
}
