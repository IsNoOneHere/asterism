alter table system_git_configs drop constraint if exists system_git_configs_validation_mode_check;
alter table system_git_configs add constraint system_git_configs_validation_mode_check
    check (validation_mode in ('auto', 'manual', 'skip'));
