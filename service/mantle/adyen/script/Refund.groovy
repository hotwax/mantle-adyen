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
import com.adyen.model.modification.RefundRequest;
import com.adyen.model.modification.ModificationResult;
import com.adyen.service.exception.ApiException;
import com.adyen.service.Modification;
import com.adyen.util.Util;
import org.moqui.entity.EntityValue

Map paymentGatewayInfo = ec.service.sync().name("mantle.adyen.AdyenServices.get#PaymentGatewayConfig").parameters([
        paymentGatewayConfigId:paymentGatewayConfigId]).call();
if (paymentGatewayInfo == null) { ec.message.addError("Could not find Adyen gateway configuration or error connecting"); return }

EntityValue payment = ec.entity.find("mantle.account.payment.Payment").condition('paymentId', paymentId).one()
if (payment == null) { ec.message.addError("Payment ${paymentId} not found"); return }

String paymentRefNum = payment.paymentRefNum
if (!paymentRefNum) {
    Map response = ec.service.sync().name("mantle.account.PaymentServices.get#AuthorizePaymentGatewayResponse")
            .parameter("paymentId", paymentId).call()
    paymentRefNum = response.paymentGatewayResponse?.referenceNum
}
if (!paymentRefNum) {
    ec.message.addError("Could not find authorization transaction ID (reference number) for Payment ${paymentId}")
    return
}

// paymentRefNum is Adyen PspReference and we can use it to seattle the payment
try {
    if (!amount) amount = payment.amount
    Client client = new Client(paymentGatewayInfo.pgcInfoMap.apiKey,Environment.TEST);
    Modification modification = new Modification(client);
    RefundRequest refundRequest = new RefundRequest();
    refundRequest.merchantAccount(paymentGatewayInfo.pgcInfoMap.merchantAccount).originalReference(paymentRefNum).reference(payment.paymentId);
    Amount modificationAmount = new Amount();
    modificationAmount.setCurrency(payment.amountUomId);
    long payAmount = (((BigDecimal) amount).multiply((new BigDecimal(10)).pow(Util.getDecimalPlaces(modificationAmount.getCurrency())))).longValue();
    modificationAmount.setValue(payAmount);
    refundRequest.setModificationAmount(modificationAmount);
    ModificationResult modificationResult = modification.refund(refundRequest);
    String response = (String) modificationResult.getResponse();
    if("[refund-received]".equals(response)) {
        Map createPgrOut = ec.service.sync().name("create#mantle.account.method.PaymentGatewayResponse").parameters([
                paymentGatewayConfigId:paymentGatewayConfigId, paymentOperationEnumId:"PgoRefund", paymentId:paymentId,
                paymentMethodId:payment.paymentMethodId, amountUomId:payment.amountUomId, amount:amount,
                referenceNum:modificationResult.getPspReference(), responseCode:response,
                transactionDate:ec.user.nowTimestamp, resultSuccess:"N", resultDeclined:"N", resultError:"N",
                resultNsf:"N", resultBadExpire:"N", resultBadCardNumber:"N"]).call()
        // out parameter
        paymentGatewayResponseId = createPgrOut.paymentGatewayResponseId
    }

} catch (ApiException e) {
    ApiError error = e.getError();
    Map createPgrOut = ec.service.sync().name("create#mantle.account.method.PaymentGatewayResponse").parameters([
            paymentGatewayConfigId:paymentGatewayConfigId, paymentOperationEnumId:"PgoAuthorize", paymentId:paymentId,
            paymentMethodId:paymentMethodId, amountUomId:payment.amountUomId, amount:amount,
            referenceNum:error.getPspReference(), responseCode:error.getErrorCode(), reasonMessage:error.getMessage(),
            transactionDate:ec.user.nowTimestamp,
            resultSuccess:"N", resultDeclined: "N", resultError: "Y",
            resultBadExpire:"N", resultBadCardNumber: "N"]).call()
    // out parameter
    paymentGatewayResponseId = createPgrOut.paymentGatewayResponseId;
    ec.message.addError("Transaction ${paymentRefNum} not found")
} catch (Exception ge) {
    ec.message.addError("Adyen exception: ${ge.toString()}")
}
