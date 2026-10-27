UPDATE wallet_account SET user_name = 'Admin'
 WHERE owner_type IN ('ESCROW','COMMISSION','EXTERNAL','MARKETING');
