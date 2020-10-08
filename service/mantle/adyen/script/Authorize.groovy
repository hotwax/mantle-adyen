/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */

import com.adyen.Client;
import com.adyen.enums.Environment;
import com.adyen.model.Amount;
import com.adyen.model.ApiError;
import com.adyen.model.checkout.PaymentsRequest;
import com.adyen.model.checkout.PaymentsResponse;
import com.adyen.service.Checkout;
import com.adyen.service.exception.ApiException;
import com.adyen.util.Util;
import org.moqui.entity.EntityValue;

EntityValue payment = ec.entity.find("mantle.account.payment.Payment").condition('paymentId', paymentId).one()
if (payment == null) { ec.message.addError("Payment ${paymentId} not found") }
EntityValue paymentMethod = (EntityValue) payment.'mantle.account.method.PaymentMethod'
EntityValue creditCard = null
if (paymentMethod.paymentMethodTypeEnumId == 'PmtCreditCard') creditCard = (EntityValue) paymentMethod.creditCard
String cvvCode = cardSecurityCode ?: creditCard?.cardSecurityCode

if (paymentMethod == null) {
        ec.message.addError("To authorize must specify an existing payment method ID")
        return
} else {
    paymentMethodId = paymentMethod.paymentMethodId
}

String partyId = paymentMethod?.ownerPartyId ?: payment.fromPartyId

if (!paymentGatewayConfigId && paymentMethod != null) paymentGatewayConfigId = paymentMethod.paymentGatewayConfigId

Map paymentGatewayInfo = ec.service.sync().name("mantle.adyen.AdyenServices.get#PaymentGatewayConfig").parameters([
        paymentGatewayConfigId:paymentGatewayConfigId]).call()
String expireDate = creditCard.expireDate;
String[] expiryDateArray = expireDate.split("/");

Client client = new Client(paymentGatewayInfo.pgcInfoMap.apiKey,Environment.TEST);

Checkout checkout = new Checkout(client);
PaymentsRequest paymentsRequest = new PaymentsRequest();
paymentsRequest.setMerchantAccount(paymentGatewayInfo.pgcInfoMap.merchantAccount);
paymentsRequest.setChannel(PaymentsRequest.ChannelEnum.WEB);
paymentsRequest.setReference(payment.paymentId);
paymentsRequest.setShopperReference(partyId);
paymentsRequest.setCountryCode("US");
Amount amount = new Amount();
amount.setCurrency(payment.amountUomId);
long payAmount = (((BigDecimal) payment.amount).multiply((new BigDecimal(10)).pow(Util.getDecimalPlaces(amount.getCurrency())))).longValue();
amount.setValue(payAmount);
paymentsRequest.setAmount(amount);
String number = creditCard.cardNumber;
String expiryMonth = expiryDateArray[0];
String expiryYear = expiryDateArray[1];
String cvc = cvvCode;
String holderName = new StringBuilder((String)paymentMethod.firstNameOnAccount).append(" ").append((String)paymentMethod.lastNameOnAccount).toString();
paymentsRequest.addCardData(number,expiryMonth, expiryYear, cvc, holderName);
paymentsRequest.setStorePaymentMethod(true);
try {
    PaymentsResponse response = checkout.payments(paymentsRequest);
    String resultCode = response.getResultCode();
    if("Authorised".equals(resultCode)) {
        println "====test authorized";
        Map createPgrOut = ec.service.sync().name("create#mantle.account.method.PaymentGatewayResponse").parameters([
                paymentGatewayConfigId:paymentGatewayConfigId, paymentOperationEnumId:"PgoAuthorize", paymentId:paymentId,
                paymentMethodId:paymentMethodId, amountUomId:payment.amountUomId, amount:payment.amount,
                referenceNum:response.getPspReference(),
                transactionDate:ec.user.nowTimestamp,
                resultSuccess:"Y", resultDeclined:"N", resultError:"N", resultNsf:"N", resultBadExpire:"N",
                resultBadCardNumber:"N"]).call()
        // out parameter
        paymentGatewayResponseId = createPgrOut.paymentGatewayResponseId
    } else if(response != null) {
        resultBadExpire = ("6".equals(response.getRefusalReasonCode()))? "Y":"N";
        Map createPgrOut = ec.service.sync().name("create#mantle.account.method.PaymentGatewayResponse").parameters([
                paymentGatewayConfigId:paymentGatewayConfigId, paymentOperationEnumId:"PgoAuthorize", paymentId:paymentId,
                paymentMethodId:paymentMethodId, amountUomId:payment.amountUomId, amount:payment.amount,
                referenceNum:response.getPspReference(), responseCode:response.getRefusalReasonCode(), reasonMessage:response.getRefusalReason(),
                transactionDate:ec.user.nowTimestamp,
                resultSuccess:"N", resultDeclined: "Y", resultError: "N",
                resultBadExpire:resultBadExpire, resultBadCardNumber: "N"]).call()
        // out parameter
        paymentGatewayResponseId = createPgrOut.paymentGatewayResponseId;
    }
} catch (ApiException e) {
    ApiError error = e.getError();
    badCardNumber = ("101".equals(error.getErrorCode())) ? "Y":"N";
    Map createPgrOut = ec.service.sync().name("create#mantle.account.method.PaymentGatewayResponse").parameters([
            paymentGatewayConfigId:paymentGatewayConfigId, paymentOperationEnumId:"PgoAuthorize", paymentId:paymentId,
            paymentMethodId:paymentMethodId, amountUomId:payment.amountUomId, amount:payment.amount,
            referenceNum:error.getPspReference(), responseCode:error.getErrorCode(), reasonMessage:error.getMessage(),
            transactionDate:ec.user.nowTimestamp,
            resultSuccess:"N", resultDeclined: "N", resultError: "Y",
            resultBadExpire:"N", resultBadCardNumber: badCardNumber]).call()
    // out parameter
    paymentGatewayResponseId = createPgrOut.paymentGatewayResponseId;
    return e.toString();
} catch (IOException e) {
    return e.toString();
}