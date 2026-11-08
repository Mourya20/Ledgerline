package com.ledgerline.repository;

import com.ledgerline.domain.AccountEntity;

public interface AccountRepositoryCustom {

    AccountEntity refresh(AccountEntity account);
}
