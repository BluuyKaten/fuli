package com.fuli.trade.practice;

import com.fuli.common.api.Result;
import com.fuli.common.api.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestController
@RequestMapping("/trade/practice")
public class PracticeController {
    private final PracticeService service;

    public PracticeController(PracticeService service) {
        this.service = service;
    }

    @PostMapping({"", "/"})
    public Result<PracticeDtos.PracticeView> create(HttpServletRequest request, @RequestBody PracticeDtos.CreateRequest body) {
        return Result.success("已创建练习；无记录日期跳过，无法区分停牌与数据缺失；采用固定训练费率及简化撮合规则", service.create(user(request), body));
    }

    @GetMapping({"", "/"})
    public Result<List<PracticeDtos.Summary>> list(HttpServletRequest request) {
        return Result.success(service.list(user(request)));
    }

    @GetMapping("/{id}")
    public Result<PracticeDtos.PracticeView> get(HttpServletRequest request, @PathVariable String id) {
        return Result.success(service.get(user(request), id));
    }

    @PostMapping("/{id}/orders")
    public Result<PracticeDtos.PracticeView> order(HttpServletRequest request, @PathVariable String id,
                                                @RequestBody PracticeDtos.OrderRequest body) {
        return Result.success(service.order(user(request), id, body));
    }

    @PostMapping("/{id}/cancel-order")
    public Result<PracticeDtos.PracticeView> cancel(HttpServletRequest request, @PathVariable String id,
                                                 @RequestBody PracticeDtos.VersionRequest body) {
        return Result.success(service.cancel(user(request), id, body));
    }

    @PostMapping("/{id}/advance")
    public Result<PracticeDtos.PracticeView> advance(HttpServletRequest request, @PathVariable String id,
                                                  @RequestBody PracticeDtos.VersionRequest body) {
        return Result.success(service.advance(user(request), id, body));
    }

    @PostMapping("/{id}/finish")
    public Result<PracticeDtos.PracticeView> finish(HttpServletRequest request, @PathVariable String id,
                                                 @RequestBody PracticeDtos.VersionRequest body) {
        return Result.success(service.finish(user(request), id, body));
    }

    @PutMapping("/{id}/annotations")
    public Result<PracticeDtos.PracticeView> annotations(HttpServletRequest request, @PathVariable String id,
                                                      @RequestBody PracticeDtos.AnnotationsRequest body) {
        return Result.success(service.annotations(user(request), id, body));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<?>> invalidJson(Exception exception) {
        // Never reflect malformed body text (which could include arbitrary caller data).
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.error(400, "请求字段格式无效"));
    }

    private static long user(HttpServletRequest request) {
        return AuthenticatedUser.requireId(request);
    }
}
