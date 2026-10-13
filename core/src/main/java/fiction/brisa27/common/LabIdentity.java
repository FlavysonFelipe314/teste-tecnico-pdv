package fiction.brisa27.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class LabIdentity {
  public String role(HttpServletRequest request) {
    return switch (String.valueOf(request.getHeader("Authorization"))) {
      case "Bearer gestor-local" -> "MANAGER";
      case "Bearer operador-local" -> "OPERATOR";
      default -> throw new BusinessException(401, "Identidade local desconhecida");
    };
  }

  public String require(HttpServletRequest request, String required) {
    String role = role(request);
    if (!role.equals(required))
      throw new BusinessException(403, "Ação não permitida para este perfil");
    return role;
  }
}
