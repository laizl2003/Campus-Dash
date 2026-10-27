package com.campusdash.presentation;

import com.campusdash.application.usecase.DeleteAccountUseCase;
import com.campusdash.presentation.auth.CurrentUser;
import com.campusdash.shared.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 账号自助操作：注销 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final DeleteAccountUseCase deleteAccount;

    public AccountController(DeleteAccountUseCase deleteAccount) {
        this.deleteAccount = deleteAccount;
    }

    @DeleteMapping
    public Result<Void> delete() {
        deleteAccount.delete(CurrentUser.get());
        return Result.ok(null);
    }
}
