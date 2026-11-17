Feature: KOELN knock-out
  A knock-out ends the note early and creates its redemption obligation.

  Background:
    Given a booked KOELN with denomination HKD 100000
    And the note has no periodic coupons
    And its knock-out level is 110
    And its first knock-out observation date is 2027-01-15
    And the associated payment date is 2027-01-19

  Scenario Outline: A closing price at or above the barrier triggers redemption
    When a closing price of <closing_price> is recorded for 2027-01-15
    Then exactly one cash redemption obligation of HKD 100000 is scheduled for 2027-01-19
    And the note terminates on 2027-01-15

    Examples:
      | closing_price |
      | 110           |
      | 111           |

  Scenario: A closing price below the barrier does not trigger redemption
    When a closing price of 109.99 is recorded for 2027-01-15
    Then no redemption obligation is created
    And the note has not terminated