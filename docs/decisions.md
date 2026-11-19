# Ayin-Relay architecture decisions

## Windows nodes are the supported product

AyinServer is the center for enrolling and controlling Windows nodes. The task layer owns scheduling, task state and delivery. Relay owns connectivity and device operation receipts. Android builds, releases and declarative mobile add-on authoring have been retired. Prior architecture and releases remain available in Git history.

## Keep the deployed node protocol

Enrollment invitations expire and are redeemed once. Persistent node authorization is specific to one node and revocable. The node also enforces its local capability policy. Keeping the deployed session format, device IDs and startup identifiers lets existing computers upgrade without enrolling again. Migrations that replace these identifiers must prove reconnect and rollback on a real device.

## Keep compatibility where there is a consumer

The repository still includes the legacy phone bridge used by the calendar automation and the Hermes adapter that exposes desktop tools. Old module paths are also used by server deployments. Retire these paths only after verifying their consumers have migrated. The Windows product design and releases do not depend on upstream Hermes development.

## Separate enrollment from release signing

The target installer is one fixed, signed binary. The enrollment invitation is short-lived and delivered via an authenticated flow independently of the binary. The operator can select which approved release to roll out, while nodes verify the package before applying it. Keep a recoverable previous version and keep device identity separate from executable files.
