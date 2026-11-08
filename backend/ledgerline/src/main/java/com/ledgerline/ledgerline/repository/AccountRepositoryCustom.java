package com.ledgerline.ledgerline.repository;

import com.ledgerline.ledgerline.domain.AccountEntity;

public interface AccountRepositoryCustom {

    AccountEntity refresh(AccountEntity account);
}
