import com.google.gson.Gson;
import model.ResponseMsg;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class HealthServlet extends HttpServlet {
  private Gson gson = new Gson();

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res)
      throws ServletException, IOException {

    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");
    res.setStatus(HttpServletResponse.SC_OK);
    res.getWriter().write(gson.toJson(new ResponseMsg("UP")));
  }
}
