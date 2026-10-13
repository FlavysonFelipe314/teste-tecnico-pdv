package fiction.brisa27.common;

public record ApiResponse(boolean success, int status, Object data, String message) {
  public static ApiResponse ok(Object data) {
    return new ApiResponse(true, 200, data, null);
  }

  public static ApiResponse error(int status, String message) {
    return new ApiResponse(false, status, null, message);
  }
}
