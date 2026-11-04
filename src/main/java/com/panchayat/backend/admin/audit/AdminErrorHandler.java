package com.panchayat.backend.admin.audit;

import com.panchayat.backend.admin.finance.AdminFinanceController;
import com.panchayat.backend.admin.manage.AdminGiftController;
import com.panchayat.backend.rewards.controller.UserRewardController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Readable { "message": ... } errors for the gift, reward and finance APIs. */
@RestControllerAdvice(basePackageClasses = {AdminGiftController.class, AdminFinanceController.class, UserRewardController.class,
        com.panchayat.backend.admin.users.AdminUsersController.class, com.panchayat.backend.search.SearchController.class,
        com.panchayat.backend.chat.controller.ChatSocialController.class, com.panchayat.backend.ranking.RankingController.class,
        com.panchayat.backend.shop.controller.ShopPurchaseController.class,
        com.panchayat.backend.redpacket.RoomRedPacketController.class})
public class AdminErrorHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        String message = e.getReason() == null ? "That didn't work" : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", message));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooBig(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("message", "That file is too large."));
    }

    /** Let Spring Security answer 403 as usual. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> denied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "You don't have access to this."));
    }
}
