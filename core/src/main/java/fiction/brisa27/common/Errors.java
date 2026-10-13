package fiction.brisa27.common;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(BusinessException.class)
  public ApiResponse business(BusinessException e) {
    return ApiResponse.error(e.status, e.getMessage());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ApiResponse badJson(Exception e) {
    return ApiResponse.error(400, "JSON inválido");
  }
}
