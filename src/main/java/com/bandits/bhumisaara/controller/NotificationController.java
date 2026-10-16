package com.bandits.bhumisaara.controller;

import com.bandits.bhumisaara.entity.UserNotificationEntity;
import com.bandits.bhumisaara.security.CurrentUserProvider;
import com.bandits.bhumisaara.service.NotificationService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/notifications")
public class NotificationController {
    private final NotificationService service;
    private final CurrentUserProvider currentUser;
    public NotificationController(NotificationService service, CurrentUserProvider currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<UserNotificationEntity> list() {
        return service.forUser(currentUser.require().getUserId());
    }

    @PostMapping("/{id}/read")
    public UserNotificationEntity read(@PathVariable Long id) {
        return service.markRead(id, currentUser.require().getUserId());
    }
}
